package com.fieldservice.identity.config;

import com.fieldservice.identity.token.StreamTicketAuthenticationFilter;
import com.fieldservice.platform.error.RestAccessDeniedHandler;
import com.fieldservice.platform.error.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Primary security filter chain for the Field Service API.
 *
 * <h3>CSRF</h3>
 * CSRF protection is disabled because every request must carry a Bearer token in the
 * {@code Authorization} header, which a cross-site form cannot set. The refresh-token
 * cookie path is narrowed to {@code /api/v1/auth} and its {@code SameSite=Strict}
 * attribute prevents ambient inclusion in cross-origin requests, providing equivalent
 * protection for the cookie-bearing logout and refresh paths without CSRF tokens.
 *
 * <h3>Deny-by-default</h3>
 * The catch-all rule ({@code anyRequest().authenticated()}) means every controller
 * endpoint requires a valid Bearer token unless explicitly listed under
 * {@code permitAll()}. Adding a new controller cannot accidentally make it public.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityFilterChainConfig {

    private final JwtDecoder jwtDecoder;
    private final RolesClaimAuthorityConverter rolesConverter;
    private final RestAuthenticationEntryPoint authEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final StreamTicketAuthenticationFilter streamTicketFilter;

    public SecurityFilterChainConfig(JwtDecoder jwtDecoder,
                                     RolesClaimAuthorityConverter rolesConverter,
                                     RestAuthenticationEntryPoint authEntryPoint,
                                     RestAccessDeniedHandler accessDeniedHandler,
                                     StreamTicketAuthenticationFilter streamTicketFilter) {
        this.jwtDecoder = jwtDecoder;
        this.rolesConverter = rolesConverter;
        this.authEntryPoint = authEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.streamTicketFilter = streamTicketFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtAuthConverter = new JwtAuthenticationConverter();
        jwtAuthConverter.setJwtGrantedAuthoritiesConverter(rolesConverter);

        http
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31_536_000)
                                .includeSubDomains(true)
                                .preload(true))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(cto -> {})
                        .referrerPolicy(rp -> rp
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
                .addFilterBefore(streamTicketFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/api-docs/**", "/v3/api-docs/**").permitAll()
                        // Stream paths: authenticated via ticket (handled by StreamTicketAuthenticationFilter)
                        .requestMatchers("/api/v1/streams/**").authenticated()
                        .requestMatchers("/api/v1/work-orders/*/copilot/stream").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthConverter))
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );

        return http.build();
    }
}
