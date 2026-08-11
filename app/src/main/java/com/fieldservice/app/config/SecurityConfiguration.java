package com.fieldservice.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.ErrorResponse;
import com.fieldservice.platform.web.TraceIdFilter;
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
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the field-service REST API.
 *
 * <h3>OAuth2 Resource Server</h3>
 * Validates incoming JWTs (RS256, issuer/audience/expiry checked).
 * The {@code roles} claim is mapped to Spring Security granted authorities
 * with the {@code ROLE_} prefix so {@code @PreAuthorize("hasRole('DISPATCHER')")}
 * works as expected.
 *
 * <h3>Uniform error envelope</h3>
 * Both the authentication entry point and the access-denied handler write the
 * same JSON {@link ErrorResponse} envelope so that filter-stage security rejections
 * are indistinguishable in shape from advice-handled exceptions.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {

    private final ObjectMapper objectMapper;

    public SecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/actuator/health", "/error").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint(this::writeUnauthorized)
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(this::writeUnauthorized)
                .accessDeniedHandler(this::writeForbidden)
            );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    private void writeUnauthorized(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res,
                                   org.springframework.security.core.AuthenticationException ex)
            throws java.io.IOException {
        writeError(res, HttpStatus.UNAUTHORIZED,
                ErrorResponse.of(ErrorCode.FORBIDDEN, "Authentication required", traceId()));
    }

    private void writeForbidden(jakarta.servlet.http.HttpServletRequest req,
                                jakarta.servlet.http.HttpServletResponse res,
                                org.springframework.security.access.AccessDeniedException ex)
            throws java.io.IOException {
        writeError(res, HttpStatus.FORBIDDEN,
                ErrorResponse.of(ErrorCode.FORBIDDEN, "Access denied", traceId()));
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse res,
                             HttpStatus status, ErrorResponse body)
            throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setHeader(TraceIdFilter.TRACE_ID_HEADER, body.traceId());
        objectMapper.writeValue(res.getOutputStream(), body);
    }

    private static String traceId() {
        String id = MDC.get(TraceIdFilter.MDC_TRACE_KEY);
        return id != null ? id : "no-trace";
    }
}
