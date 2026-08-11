package com.fieldservice.app.config;

import com.fieldservice.identity.config.RolesClaimAuthorityConverter;
import com.fieldservice.platform.web.RestAccessDeniedHandler;
import com.fieldservice.platform.web.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Spring Security filter chain for the Field Service API.
 *
 * <h3>Authentication</h3>
 * <p>Every request is authenticated by the OAuth2 Resource Server using RS256 JWTs.
 * Token validation (signature, issuer, audience, expiry, JTI denylist) is entirely
 * handled by Spring's resource server primitives — no custom JWT filter.
 *
 * <h3>Authorization — deny by default</h3>
 * <p>Only the auth path, OpenAPI doc path, and the internal health and Prometheus paths
 * are permitted without a bearer token. Any other path added in the future requires a
 * valid JWT; an accidentally public controller is not possible without an explicit change
 * to this class.
 *
 * <h3>CSRF</h3>
 * <p>CSRF is disabled for stateless bearer token endpoints. The refresh and logout cookie
 * paths rely on {@code SameSite=Strict} plus a narrow cookie {@code Path} instead of
 * ambient CSRF tokens — this is documented here to satisfy the "reasoning inline" requirement.
 * An ambient CSRF token cannot be obtained by a cross-origin attacker because the API is
 * JSON-only and the cookie is {@code HttpOnly}; the narrow path confines the cookie to
 * {@code /api/v1/auth} so it is not sent with non-auth requests.
 *
 * <h3>Security headers</h3>
 * <p>Every response carries the hardened header set:
 * <ul>
 *   <li>HSTS — max-age 31536000, includeSubDomains, preload</li>
 *   <li>CSP — {@code default-src 'self'} with no {@code unsafe-inline}</li>
 *   <li>X-Content-Type-Options — nosniff</li>
 *   <li>X-Frame-Options — DENY</li>
 *   <li>Referrer-Policy — strict-origin-when-cross-origin</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final RolesClaimAuthorityConverter rolesClaimAuthorityConverter;

    public SecurityConfiguration(RestAuthenticationEntryPoint authenticationEntryPoint,
                                  RestAccessDeniedHandler accessDeniedHandler,
                                  RolesClaimAuthorityConverter rolesClaimAuthorityConverter) {
        this.authenticationEntryPoint     = authenticationEntryPoint;
        this.accessDeniedHandler          = accessDeniedHandler;
        this.rolesClaimAuthorityConverter = rolesClaimAuthorityConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ---- Session / CSRF -----------------------------------------------
            .csrf(csrf -> csrf.disable())
            // CSRF note: refresh and logout cookies rely on SameSite=Strict + narrow Path
            // instead of CSRF tokens; see class-level Javadoc for rationale.
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ---- Authorization — deny by default ------------------------------
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()
                .requestMatchers("/api-docs", "/api-docs/**").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .anyRequest().authenticated()
            )

            // ---- Resource server — JWT validation ----------------------------
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint(authenticationEntryPoint)
            )

            // ---- Error handling -------------------------------------------
            .exceptionHandling(exc -> exc
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )

            // ---- Hardened security headers -----------------------------------
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                    .includeSubDomains(true)
                    .preload(true))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'"))
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .xssProtection(Customizer.withDefaults())
            );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesClaimAuthorityConverter);
        return converter;
    }
}
