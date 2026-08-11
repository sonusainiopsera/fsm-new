package com.fieldservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ErrorEnvelope;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.time.Instant;

/**
 * Spring Security configuration for the Field Service API.
 *
 * <p>Configures:
 * <ul>
 *   <li>Stateless JWT-based OAuth2 Resource Server authentication</li>
 *   <li>JWT {@code roles} claim → Spring Security granted authorities (ROLE_-prefixed)</li>
 *   <li>Method-level security via {@code @PreAuthorize} / {@code @PostAuthorize}</li>
 *   <li>Uniform 403 and 401 error responses with no existence disclosure</li>
 * </ul>
 *
 * <p>The {@link JwtGrantedAuthoritiesConverter} maps the custom {@code roles} claim to
 * {@code GrantedAuthority} instances with the {@code ROLE_} prefix, making them compatible
 * with Spring Security's {@code hasRole()} and {@code @PreAuthorize("hasRole('DISPATCHER')")} checks.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfiguration {

    private final ObjectMapper objectMapper;

    public SecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()) // CSRF not needed for stateless JWT API
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(uniformAuthenticationEntryPoint())
                        .accessDeniedHandler(uniformAccessDeniedHandler())
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(uniformAuthenticationEntryPoint())
                        .accessDeniedHandler(uniformAccessDeniedHandler())
                );

        return http.build();
    }

    /**
     * Configures JWT-to-authority mapping.
     *
     * <p>Reads the {@code roles} claim (list of strings) from the JWT and converts each
     * entry to a {@code GrantedAuthority} with the {@code ROLE_} prefix. For example,
     * a JWT containing {@code "roles": ["DISPATCHER"]} produces an authority
     * {@code ROLE_DISPATCHER}, which satisfies {@code hasRole("DISPATCHER")} checks.
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

    /**
     * Uniform 401 entry point — used for missing or invalid tokens.
     *
     * <p>Returns a structured {@link ErrorEnvelope} with no additional details
     * that could aid a credential-stuffing or enumeration attack.
     */
    @Bean
    public AuthenticationEntryPoint uniformAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ErrorEnvelope envelope = new ErrorEnvelope(
                    ErrorEnvelope.Code.UNAUTHENTICATED,
                    "Authentication required.",
                    traceId(),
                    Instant.now()
            );
            objectMapper.writeValue(response.getWriter(), envelope);
        };
    }

    /**
     * Uniform 403 handler — used for access denied from Spring Security method security.
     *
     * <p>Returns a deliberately generic response with no existence disclosure.
     */
    @Bean
    public AccessDeniedHandler uniformAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ErrorEnvelope envelope = new ErrorEnvelope(
                    ErrorEnvelope.Code.ACCESS_DENIED,
                    "Access denied.",
                    traceId(),
                    Instant.now()
            );
            objectMapper.writeValue(response.getWriter(), envelope);
        };
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "none";
    }
}
