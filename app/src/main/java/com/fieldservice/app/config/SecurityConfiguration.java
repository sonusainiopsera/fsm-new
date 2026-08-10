package com.fieldservice.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Spring Security configuration for the field-service REST API.
 *
 * <h3>OAuth2 Resource Server</h3>
 * Validates incoming JWTs (RS256, issuer/audience/expiry checked).
 * The {@code roles} claim is mapped to Spring Security granted authorities
 * with the {@code ROLE_} prefix so {@code @PreAuthorize("hasRole('DISPATCHER')")}
 * works as expected.
 *
 * <h3>Method Security</h3>
 * {@code @EnableMethodSecurity} activates {@code @PreAuthorize} / {@code @PostAuthorize}
 * processing on service methods. Every public service method in the domain modules
 * must carry an authorization annotation — the {@code MethodSecurityAnnotationTest}
 * asserts this invariant.
 *
 * <h3>Stateless</h3>
 * No HTTP session is created. All state is in the JWT. CSRF protection is
 * disabled (bearer-token APIs are not vulnerable to CSRF).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            );

        return http.build();
    }

    /**
     * Maps the JWT {@code roles} claim to Spring Security granted authorities
     * with the {@code ROLE_} prefix. The default claim name for roles is
     * overridden from {@code scope} to {@code roles}.
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
