package com.fieldservice.identity;

import com.fieldservice.identity.config.RolesClaimAuthorityConverter;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link RolesClaimAuthorityConverter} — no Spring context.
 */
class RolesClaimAuthorityConverterTest {

    private final RolesClaimAuthorityConverter converter = new RolesClaimAuthorityConverter();

    @Test
    void dispatcher_role_mapsToCORRECTAuthority() {
        Collection<GrantedAuthority> authorities = converter.convert(jwt(List.of("DISPATCHER")));
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_DISPATCHER");
    }

    @Test
    void manager_role_mapsToCORRECTAuthority() {
        assertThat(converter.convert(jwt(List.of("MANAGER"))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MANAGER");
    }

    @Test
    void technician_role_mapsCorrectly() {
        assertThat(converter.convert(jwt(List.of("TECHNICIAN"))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TECHNICIAN");
    }

    @Test
    void customer_role_mapsCorrectly() {
        assertThat(converter.convert(jwt(List.of("CUSTOMER"))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void admin_role_mapsCorrectly() {
        assertThat(converter.convert(jwt(List.of("ADMIN"))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void multipleRoles_allMapped() {
        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(List.of("DISPATCHER", "MANAGER")));
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_DISPATCHER", "ROLE_MANAGER");
    }

    @Test
    void absentRolesClaim_returnsEmptyList_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> {
            Collection<GrantedAuthority> result = converter.convert(jwt(null));
            assertThat(result).isEmpty();
        });
    }

    @Test
    void emptyRolesList_returnsEmptyList() {
        Collection<GrantedAuthority> result = converter.convert(jwt(List.of()));
        assertThat(result).isEmpty();
    }

    @Test
    void unknownRoleValue_grantsROLE_prefixedAuthority_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> {
            Collection<GrantedAuthority> result = converter.convert(jwt(List.of("UNKNOWN_ROLE")));
            assertThat(result).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_UNKNOWN_ROLE");
        });
    }

    private static Jwt jwt(List<String> roles) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900));
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder.build();
    }
}
