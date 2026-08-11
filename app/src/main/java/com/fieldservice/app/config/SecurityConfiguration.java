package com.fieldservice.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the Field Service API.
 *
 * <h3>Authentication</h3>
 * <p>The application is an OAuth2 Resource Server that validates RS256-signed JWTs issued
 * by the configured OIDC provider. Token validation (signature, issuer, audience, expiry)
 * is handled by Spring's built-in resource server support — no hand-rolled JWT filter.
 *
 * <h3>Role mapping</h3>
 * <p>The JWT carries a {@code roles} claim with plain role names (e.g. {@code DISPATCHER}).
 * {@link JwtGrantedAuthoritiesConverter} maps these to Spring Security granted authorities
 * with the {@code ROLE_} prefix (e.g. {@code ROLE_DISPATCHER}), enabling standard
 * {@code hasRole("DISPATCHER")} checks in {@code @PreAuthorize} annotations.
 *
 * <h3>Method security</h3>
 * <p>{@link EnableMethodSecurity} activates Spring's annotation-driven method security so
 * that {@code @PreAuthorize} annotations on service methods are honoured. Every publicly
 * reachable service method in a domain module must carry an authorization annotation; the
 * ArchUnit fitness test ({@code ServiceAuthorizationAnnotationTest}) enforces this at build
 * time.
 *
 * <h3>Session management</h3>
 * <p>The API is fully stateless — no server-side sessions are created or consulted. All
 * authorization state lives in the JWT.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * Primary security filter chain.
     *
     * <ul>
     *   <li>Actuator health endpoint is open (used by load-balancer health checks).</li>
     *   <li>All other requests require a valid Bearer JWT.</li>
     *   <li>Sessions are never created — fully stateless.</li>
     *   <li>CSRF is disabled because the API is stateless JWT-authenticated.</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }

    /**
     * Converter that maps the {@code roles} JWT claim to Spring Security granted
     * authorities with the {@code ROLE_} prefix.
     *
     * <p>Example: JWT {@code "roles": ["DISPATCHER", "ADMIN"]} →
     * granted authorities {@code [ROLE_DISPATCHER, ROLE_ADMIN]}.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
