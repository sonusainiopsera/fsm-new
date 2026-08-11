package com.fieldservice.identity.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Maps the {@code roles} JWT claim to Spring Security {@link GrantedAuthority} values with
 * the {@code ROLE_} prefix.
 *
 * <p>Example: JWT {@code "roles": ["DISPATCHER", "ADMIN"]} produces authorities
 * {@code [ROLE_DISPATCHER, ROLE_ADMIN]}.
 *
 * <p>Edge cases handled without throwing:
 * <ul>
 *   <li>Absent {@code roles} claim → empty authority collection</li>
 *   <li>Empty list → empty authority collection</li>
 *   <li>Unknown role names → accepted as-is; no authority is granted for an unknown
 *       role because no {@code @PreAuthorize("hasRole('UNKNOWN_ROLE')")} annotation
 *       will match it</li>
 *   <li>Null entries in the list → silently skipped</li>
 * </ul>
 */
@Component
public class RolesClaimAuthorityConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object rolesObj = jwt.getClaims().get(ROLES_CLAIM);
        if (rolesObj == null) {
            return Collections.emptyList();
        }

        if (!(rolesObj instanceof List<?> rawList)) {
            return Collections.emptyList();
        }

        return rawList.stream()
                .filter(Objects::nonNull)
                .map(role -> role instanceof String s ? s : role.toString())
                .map(roleName -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + roleName))
                .toList();
    }
}
