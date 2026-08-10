package com.fieldservice.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link AccessScopeContext} scope resolution from JWT claims.
 * Covers all five roles (DISPATCHER, ADMIN, MANAGER, TECHNICIAN, CUSTOMER),
 * the missing-roles denial, the multi-account customer union case,
 * and the request-level caching behaviour.
 */
class AccessScopeResolutionTest {

    private static final UUID ACCOUNT_1 = UUID.randomUUID();
    private static final UUID ACCOUNT_2 = UUID.randomUUID();

    @Test
    void dispatcher_scope_has_permit_all() {
        setAuth(jwtWith("user-1", List.of("DISPATCHER"), null, null));
        AccessScopeContext ctx = new AccessScopeContext();
        AccessScope scope = ctx.get();

        assertThat(scope.userId()).isEqualTo("user-1");
        assertThat(scope.roles()).containsExactly("DISPATCHER");
        assertThat(scope.isDispatcher()).isTrue();
        assertThat(scope.isPermitAll()).isTrue();
        assertThat(scope.technicianId()).isNull();
        assertThat(scope.customerAccountIds()).isEmpty();
    }

    @Test
    void admin_scope_has_permit_all() {
        setAuth(jwtWith("admin-1", List.of("ADMIN"), null, null));
        AccessScope scope = new AccessScopeContext().get();
        assertThat(scope.isAdmin()).isTrue();
        assertThat(scope.isPermitAll()).isTrue();
    }

    @Test
    void manager_scope_has_permit_all() {
        setAuth(jwtWith("mgr-1", List.of("MANAGER"), null, null));
        AccessScope scope = new AccessScopeContext().get();
        assertThat(scope.isManager()).isTrue();
        assertThat(scope.isPermitAll()).isTrue();
    }

    @Test
    void technician_scope_carries_technician_id() {
        setAuth(jwtWith("user-tech", List.of("TECHNICIAN"), "tech-42", null));
        AccessScope scope = new AccessScopeContext().get();

        assertThat(scope.isTechnician()).isTrue();
        assertThat(scope.isPermitAll()).isFalse();
        assertThat(scope.technicianId()).isEqualTo("tech-42");
    }

    @Test
    void customer_scope_carries_account_ids() {
        setAuth(jwtWith("cust-1", List.of("CUSTOMER"), null,
                List.of(ACCOUNT_1.toString(), ACCOUNT_2.toString())));
        AccessScope scope = new AccessScopeContext().get();

        assertThat(scope.isCustomer()).isTrue();
        assertThat(scope.isPermitAll()).isFalse();
        assertThat(scope.customerAccountIds()).containsExactlyInAnyOrder(ACCOUNT_1, ACCOUNT_2);
    }

    @Test
    void customer_with_two_accounts_sees_union() {
        UUID a1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID a2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        setAuth(jwtWith("multi-cust", List.of("CUSTOMER"), null,
                List.of(a1.toString(), a2.toString())));
        AccessScope scope = new AccessScopeContext().get();
        assertThat(scope.customerAccountIds()).hasSize(2).contains(a1, a2);
    }

    @Test
    void missing_roles_claim_throws_scoped_access_denied() {
        setAuth(jwtWith("user-x", null, null, null));
        assertThatThrownBy(() -> new AccessScopeContext().get())
                .isInstanceOf(ScopedAccessDeniedException.class)
                .hasMessageContaining("roles");
    }

    @Test
    void empty_roles_claim_throws_scoped_access_denied() {
        setAuth(jwtWith("user-x", List.of(), null, null));
        assertThatThrownBy(() -> new AccessScopeContext().get())
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    @Test
    void no_authentication_throws_scoped_access_denied() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> new AccessScopeContext().get())
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    @Test
    void scope_is_cached_within_same_context_instance() {
        setAuth(jwtWith("user-1", List.of("DISPATCHER"), null, null));
        AccessScopeContext ctx = new AccessScopeContext();
        AccessScope first = ctx.get();
        AccessScope second = ctx.get();
        assertThat(first).isSameAs(second);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void setAuth(Jwt jwt) {
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private Jwt jwtWith(String subject, List<String> roles, String technicianId,
                        List<String> customerAccountIds) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", subject);
        if (roles != null) claims.put("roles", roles);
        if (technicianId != null) claims.put("technician_id", technicianId);
        if (customerAccountIds != null) claims.put("customer_account_ids", customerAccountIds);

        return Jwt.withTokenValue("test-token")
                .headers(h -> h.put("alg", "RS256"))
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
