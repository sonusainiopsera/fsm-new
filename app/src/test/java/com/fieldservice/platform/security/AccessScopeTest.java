package com.fieldservice.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccessScope} and {@link AccessScopeResolver}.
 *
 * <p>Covers: all five roles, missing claims, empty roles, unrecognised roles,
 * multi-account customer union, and null/malformed claim values.
 */
class AccessScopeTest {

    private final AccessScopeResolver resolver = new AccessScopeResolver();

    private static final UUID TECH_ID      = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID ACME_ACCT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA_ACCT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    // -------------------------------------------------------------------------
    // DISPATCHER
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DISPATCHER principal")
    class DispatcherTests {

        @Test
        @DisplayName("resolves to privileged scope with empty technician and account ids")
        void dispatcher_resolves_privileged() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of("DISPATCHER"), null, null));

            assertThat(scope.hasRole("DISPATCHER")).isTrue();
            assertThat(scope.isPrivileged()).isTrue();
            assertThat(scope.isTechnician()).isFalse();
            assertThat(scope.isCustomer()).isFalse();
            assertThat(scope.technicianId()).isNull();
            assertThat(scope.customerAccountIds()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // ADMIN
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ADMIN principal")
    class AdminTests {

        @Test
        @DisplayName("resolves to privileged scope")
        void admin_resolves_privileged() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of("ADMIN"), null, null));

            assertThat(scope.isPrivileged()).isTrue();
            assertThat(scope.hasRole("ADMIN")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // MANAGER
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("MANAGER principal")
    class ManagerTests {

        @Test
        @DisplayName("resolves to privileged scope")
        void manager_resolves_privileged() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of("MANAGER"), null, null));

            assertThat(scope.isPrivileged()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // TECHNICIAN
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TECHNICIAN principal")
    class TechnicianTests {

        @Test
        @DisplayName("resolves technicianId from claim")
        void technician_resolves_technician_id() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of("TECHNICIAN"),
                    TECH_ID.toString(), null));

            assertThat(scope.isTechnician()).isTrue();
            assertThat(scope.technicianId()).isEqualTo(TECH_ID);
            assertThat(scope.isPrivileged()).isFalse();
            assertThat(scope.isCustomer()).isFalse();
        }

        @Test
        @DisplayName("missing technician_id claim → technicianId is null (predicate factory will deny)")
        void technician_missing_technician_id_yields_null() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of("TECHNICIAN"),
                    null, null));

            assertThat(scope.isTechnician()).isTrue();
            assertThat(scope.technicianId()).isNull();
        }

        @Test
        @DisplayName("malformed technician_id claim → technicianId is null")
        void technician_malformed_technician_id_yields_null() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of("TECHNICIAN"),
                    "not-a-uuid", null));

            assertThat(scope.technicianId()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // CUSTOMER
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CUSTOMER principal")
    class CustomerTests {

        @Test
        @DisplayName("resolves single customer account id")
        void customer_single_account() {
            AccessScope scope = resolver.resolve(
                    jwtAuth(UUID.randomUUID(), List.of("CUSTOMER"), null,
                            List.of(ACME_ACCT_ID.toString())));

            assertThat(scope.isCustomer()).isTrue();
            assertThat(scope.customerAccountIds()).containsExactlyInAnyOrder(ACME_ACCT_ID);
        }

        @Test
        @DisplayName("multi-account customer union: both account ids are present")
        void customer_multi_account_union() {
            AccessScope scope = resolver.resolve(
                    jwtAuth(UUID.randomUUID(), List.of("CUSTOMER"), null,
                            List.of(ACME_ACCT_ID.toString(), BETA_ACCT_ID.toString())));

            assertThat(scope.isCustomer()).isTrue();
            assertThat(scope.customerAccountIds())
                    .containsExactlyInAnyOrder(ACME_ACCT_ID, BETA_ACCT_ID);
        }

        @Test
        @DisplayName("empty customer_account_ids claim → empty set (predicate factory will deny)")
        void customer_empty_account_ids() {
            AccessScope scope = resolver.resolve(
                    jwtAuth(UUID.randomUUID(), List.of("CUSTOMER"), null, List.of()));

            assertThat(scope.isCustomer()).isTrue();
            assertThat(scope.customerAccountIds()).isEmpty();
        }

        @Test
        @DisplayName("malformed UUID in customer_account_ids is silently dropped")
        void customer_malformed_account_id_dropped() {
            AccessScope scope = resolver.resolve(
                    jwtAuth(UUID.randomUUID(), List.of("CUSTOMER"), null,
                            List.of("not-a-uuid", ACME_ACCT_ID.toString())));

            // The malformed entry is dropped; the valid one survives.
            assertThat(scope.customerAccountIds()).containsExactly(ACME_ACCT_ID);
        }
    }

    // -------------------------------------------------------------------------
    // Deny cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Deny cases")
    class DenyCases {

        @Test
        @DisplayName("null roles claim → empty scope (deny all)")
        void null_roles_yields_empty_scope() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), null, null, null));

            assertThat(scope.isEmpty()).isTrue();
            assertThat(scope.roles()).isEmpty();
        }

        @Test
        @DisplayName("empty roles claim → empty scope")
        void empty_roles_yields_empty_scope() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of(), null, null));

            assertThat(scope.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("unrecognised role → scope carries the role name but all predicates deny")
        void unrecognised_role_in_scope() {
            AccessScope scope = resolver.resolve(jwtAuth(UUID.randomUUID(), List.of("ROBOT"), null, null));

            assertThat(scope.roles()).containsExactly("ROBOT");
            assertThat(scope.isPrivileged()).isFalse();
            assertThat(scope.isTechnician()).isFalse();
            assertThat(scope.isCustomer()).isFalse();
        }

        @Test
        @DisplayName("null authentication → ScopedAccessDeniedException")
        void null_authentication_throws() {
            assertThatThrownBy(() -> resolver.resolve(null))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        @DisplayName("ROLE_ prefix in JWT claim is stripped during resolution")
        void role_prefix_stripped() {
            AccessScope scope = resolver.resolve(
                    jwtAuth(UUID.randomUUID(), List.of("ROLE_DISPATCHER"), null, null));

            assertThat(scope.hasRole("DISPATCHER")).isTrue();
            assertThat(scope.hasRole("ROLE_DISPATCHER")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // AccessScope record invariants
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AccessScope record immutability")
    class RecordTests {

        @Test
        @DisplayName("null roles in constructor becomes empty set")
        void null_roles_becomes_empty() {
            AccessScope scope = new AccessScope(UUID.randomUUID(), null, null, null);
            assertThat(scope.roles()).isEmpty();
            assertThat(scope.customerAccountIds()).isEmpty();
        }

        @Test
        @DisplayName("roles set is unmodifiable")
        void roles_set_is_unmodifiable() {
            AccessScope scope = new AccessScope(UUID.randomUUID(), Set.of("DISPATCHER"), null, null);
            assertThatThrownBy(() -> scope.roles().add("HACKER"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
    jwtAuth(UUID subject, List<String> roles, String technicianId, List<String> customerAccountIds) {
        Instant now = Instant.now();
        var headers = Map.<String, Object>of("alg", "RS256");
        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", subject.toString());
        claims.put("iss", "https://test.fieldservice.local");
        claims.put("iat", now);
        claims.put("exp", now.plusSeconds(300));
        if (roles != null) {
            claims.put("roles", roles);
        }
        if (technicianId != null) {
            claims.put("technician_id", technicianId);
        }
        if (customerAccountIds != null && !customerAccountIds.isEmpty()) {
            claims.put("customer_account_ids", customerAccountIds);
        }
        Jwt jwt = new Jwt("test-token", now, now.plusSeconds(300), headers, claims);
        return new org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationToken(jwt);
    }
}
