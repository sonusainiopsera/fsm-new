package com.fieldservice.identity.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JWT {@code roles} claim to Spring Security {@link GrantedAuthority} values.
 *
 * <p>Each role string is prefixed with {@code ROLE_} to align with Spring Security's
 * {@code hasRole()} convention. An absent, null, empty or unrecognised roles claim
 * grants no authority and never throws.
 *
 * <p>Example: {@code "roles": ["DISPATCHER"]} → {@code ROLE_DISPATCHER}
 */
@Component
public class RolesClaimAuthorityConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            return Collections.emptyList();
        }
        return roles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}
