package com.fieldservice.identity.token;

import com.fieldservice.identity.config.RolesClaimAuthorityConverter;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link RolesClaimAuthorityConverter} — no Spring context.
 */
class RolesClaimAuthorityConverterTest {

    private final RolesClaimAuthorityConverter converter = new RolesClaimAuthorityConverter();

    @Test
    void maps_all_five_ratified_roles_to_role_prefixed_authorities() {
        Jwt jwt = jwt(Map.of("roles", List.of("ADMIN", "DISPATCHER", "TECHNICIAN", "MANAGER", "CUSTOMER")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_ADMIN", "ROLE_DISPATCHER", "ROLE_TECHNICIAN",
                        "ROLE_MANAGER", "ROLE_CUSTOMER");
    }

    @Test
    void absent_roles_claim_grants_no_authority_and_does_not_throw() {
        Jwt jwt = jwt(Map.of()); // no roles claim

        assertThatNoException().isThrownBy(() -> {
            Collection<GrantedAuthority> authorities = converter.convert(jwt);
            assertThat(authorities).isEmpty();
        });
    }

    @Test
    void empty_roles_list_grants_no_authority() {
        Jwt jwt = jwt(Map.of("roles", List.of()));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void unknown_role_name_grants_an_authority_with_role_prefix_and_does_not_throw() {
        // Unknown roles are not rejected — no @PreAuthorize matches them, granting nothing in practice
        Jwt jwt = jwt(Map.of("roles", List.of("UNKNOWN_ROLE")));

        assertThatNoException().isThrownBy(() -> {
            Collection<GrantedAuthority> authorities = converter.convert(jwt);
            assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_UNKNOWN_ROLE");
        });
    }

    @Test
    void null_entries_in_roles_list_are_silently_skipped() {
        // Build a list with a null entry via raw type
        List<Object> roles = new java.util.ArrayList<>();
        roles.add("ADMIN");
        roles.add(null);
        roles.add("DISPATCHER");
        Jwt jwt = jwt(Map.of("roles", roles));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DISPATCHER");
    }

    @Test
    void single_role_is_mapped_correctly() {
        Jwt jwt = jwt(Map.of("roles", List.of("TECHNICIAN")));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TECHNICIAN");
    }

    private static Jwt jwt(Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        Map<String, Object> headers = Map.of("alg", "RS256");
        Map<String, Object> claims  = new java.util.HashMap<>(extraClaims);
        claims.put("sub", "test-user");
        claims.put("iss", "https://auth.fieldservice.local");
        return Jwt.withTokenValue("test.token.value")
                .headers(h -> h.putAll(headers))
                .claims(c -> c.putAll(claims))
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(870))
                .build();
    }
}
