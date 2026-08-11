package com.fieldservice.platform.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccessScopeResolver}.
 *
 * <p>Tests cover all five roles, missing/empty roles claim, and multi-account customer users.
 */
class AccessScopeResolverTest {

    // Fixed test UUIDs
    private static final UUID USER_ID      = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID TECH_ID      = UUID.fromString("11111111-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT_ID_1 = UUID.fromString("11111111-0000-0000-0000-000000000003");
    private static final UUID ACCOUNT_ID_2 = UUID.fromString("11111111-0000-0000-0000-000000000004");

    private AccessScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AccessScopeResolver();
        SecurityContextHolder.clearContext();
    }

    // -- Helper -------------------------------------------------------------------

    private void setJwtPrincipal(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .claims(c -> c.putAll(claims))
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of());
        auth.setAuthenticated(true);
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    // -- Tests -------------------------------------------------------------------

    @Nested
    @DisplayName("DISPATCHER role")
    class DispatcherRole {
        @Test
        void resolvesPrivilegedScope() {
            setJwtPrincipal(Map.of("roles", List.of("DISPATCHER")));

            AccessScope scope = resolver.resolve();

            assertThat(scope.userId()).isEqualTo(USER_ID);
            assertThat(scope.roles()).contains(Role.DISPATCHER);
            assertThat(scope.isPrivileged()).isTrue();
            assertThat(scope.technicianId()).isNull();
            assertThat(scope.customerAccountIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ADMIN role")
    class AdminRole {
        @Test
        void resolvesPrivilegedScope() {
            setJwtPrincipal(Map.of("roles", List.of("ADMIN")));

            AccessScope scope = resolver.resolve();

            assertThat(scope.isPrivileged()).isTrue();
            assertThat(scope.roles()).contains(Role.ADMIN);
        }
    }

    @Nested
    @DisplayName("MANAGER role")
    class ManagerRole {
        @Test
        void resolvesPrivilegedScope() {
            setJwtPrincipal(Map.of("roles", List.of("MANAGER")));

            AccessScope scope = resolver.resolve();

            assertThat(scope.isPrivileged()).isTrue();
        }
    }

    @Nested
    @DisplayName("TECHNICIAN role")
    class TechnicianRole {
        @Test
        void resolvesScopedWithTechnicianId() {
            setJwtPrincipal(Map.of(
                    "roles", List.of("TECHNICIAN"),
                    "technicianId", TECH_ID.toString()
            ));

            AccessScope scope = resolver.resolve();

            assertThat(scope.isTechnician()).isTrue();
            assertThat(scope.isPrivileged()).isFalse();
            assertThat(scope.technicianId()).isEqualTo(TECH_ID);
            assertThat(scope.customerAccountIds()).isEmpty();
        }

        @Test
        void throwsWhenTechnicianIdMissing() {
            setJwtPrincipal(Map.of("roles", List.of("TECHNICIAN")));

            assertThatThrownBy(() -> resolver.resolve())
                    .isInstanceOf(ScopedAccessDeniedException.class)
                    .hasMessageContaining("technicianId");
        }

        @Test
        void throwsWhenTechnicianIdInvalidUuid() {
            setJwtPrincipal(Map.of(
                    "roles", List.of("TECHNICIAN"),
                    "technicianId", "not-a-uuid"
            ));

            assertThatThrownBy(() -> resolver.resolve())
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("CUSTOMER role")
    class CustomerRole {
        @Test
        void resolvesSingleAccountScope() {
            setJwtPrincipal(Map.of(
                    "roles", List.of("CUSTOMER"),
                    "customerAccountIds", List.of(ACCOUNT_ID_1.toString())
            ));

            AccessScope scope = resolver.resolve();

            assertThat(scope.isCustomer()).isTrue();
            assertThat(scope.isPrivileged()).isFalse();
            assertThat(scope.technicianId()).isNull();
            assertThat(scope.customerAccountIds()).containsExactly(ACCOUNT_ID_1);
        }

        @Test
        void resolvesMultiAccountScope_unionOfBothAccounts() {
            // A customer user linked to two accounts must see the union
            setJwtPrincipal(Map.of(
                    "roles", List.of("CUSTOMER"),
                    "customerAccountIds", List.of(
                            ACCOUNT_ID_1.toString(),
                            ACCOUNT_ID_2.toString()
                    )
            ));

            AccessScope scope = resolver.resolve();

            assertThat(scope.customerAccountIds())
                    .hasSize(2)
                    .containsExactlyInAnyOrder(ACCOUNT_ID_1, ACCOUNT_ID_2);
        }

        @Test
        void resolvesScopeWithNoLinkedAccounts_emptySet() {
            // A customer with no linked accounts sees nothing; not an error
            setJwtPrincipal(Map.of(
                    "roles", List.of("CUSTOMER"),
                    "customerAccountIds", Collections.emptyList()
            ));

            AccessScope scope = resolver.resolve();

            assertThat(scope.customerAccountIds()).isEmpty();
        }

        @Test
        void throwsWhenAccountIdInvalidUuid() {
            setJwtPrincipal(Map.of(
                    "roles", List.of("CUSTOMER"),
                    "customerAccountIds", List.of("not-a-uuid")
            ));

            assertThatThrownBy(() -> resolver.resolve())
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Missing or empty roles claim")
    class MissingRoles {
        @Test
        void throwsWhenRolesClaimAbsent() {
            setJwtPrincipal(Map.of()); // no roles claim

            assertThatThrownBy(() -> resolver.resolve())
                    .isInstanceOf(ScopedAccessDeniedException.class)
                    .hasMessageContaining("roles");
        }

        @Test
        void throwsWhenRolesClaimEmpty() {
            setJwtPrincipal(Map.of("roles", Collections.emptyList()));

            assertThatThrownBy(() -> resolver.resolve())
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        void throwsWhenNotAuthenticated() {
            SecurityContextHolder.clearContext(); // no authentication

            assertThatThrownBy(() -> resolver.resolve())
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Request-scope caching")
    class Caching {
        @Test
        void returnsTheSameInstanceOnRepeatCalls() {
            setJwtPrincipal(Map.of("roles", List.of("DISPATCHER")));

            AccessScope first = resolver.resolve();
            AccessScope second = resolver.resolve();

            assertThat(first).isSameAs(second);
        }
    }

    @Nested
    @DisplayName("Role prefix normalisation")
    class RolePrefixNormalisation {
        @Test
        void acceptsRolesWithAndWithoutRolePrefix() {
            // Roles claim may come with or without the ROLE_ prefix
            setJwtPrincipal(Map.of("roles", List.of("ROLE_DISPATCHER")));

            AccessScope scope = resolver.resolve();

            assertThat(scope.isPrivileged()).isTrue();
            assertThat(scope.hasRole("DISPATCHER")).isTrue();
            assertThat(scope.hasRole("ROLE_DISPATCHER")).isTrue();
        }
    }
}
