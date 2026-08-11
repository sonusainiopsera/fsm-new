package com.fieldservice.app.security;

import com.fieldservice.app.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token-shape negative tests for roles that are syntactically valid but semantically empty.
 *
 * <p>Complements {@code SecurityFilterChainIT} (which tests cryptographically invalid tokens:
 * expired, wrong issuer, wrong audience, bad signature) with cases where the token itself is
 * cryptographically valid but grants no usable authority:
 * <ul>
 *   <li><strong>Lowercase role</strong> — {@code roles: ["dispatcher"]} produces authority
 *       {@code ROLE_dispatcher} which does NOT match {@code hasRole('DISPATCHER')} (Spring
 *       Security comparisons are case-sensitive). The token is authenticated but the caller
 *       is denied by {@code @PreAuthorize}. Result: HTTP 403, not 401.</li>
 *   <li><strong>Unknown role</strong> — {@code roles: ["SUPERUSER"]} likewise grants no
 *       matching authority for any existing {@code @PreAuthorize} expression. Result: 403.</li>
 *   <li><strong>Empty roles list</strong> — token is authenticated (signature, issuer,
 *       audience all valid) but grants no authority. Role-guarded endpoints return 403.</li>
 * </ul>
 *
 * <p>These tests prove that a token accepted by the resource server cannot silently escalate
 * privilege just by using a different casing or an invented role string.
 *
 * <p>Note: the role-to-authority mapping unit tests are in
 * {@code RolesClaimAuthorityConverterTest}. This class adds the HTTP integration layer.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql(scripts = "/db/fixtures.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/db/cleanup.sql",
     executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class TokenShapeNegativeTest {

    @Autowired
    MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // Lowercase role — authenticated but no authority granted
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Lowercase role 'dispatcher' does not grant DISPATCHER authority (403, not 401)")
    void lowercase_role_is_authenticated_but_not_authorised() throws Exception {
        Jwt jwt = buildJwt(List.of("dispatcher")); // lowercase — no matching authority

        mockMvc.perform(get("/api/v1/work-orders")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN")); // not UNAUTHENTICATED
    }

    @Test
    @DisplayName("Lowercase role 'admin' does not grant ADMIN authority — ADMIN-only endpoint returns 403")
    void lowercase_admin_role_cannot_access_admin_endpoint() throws Exception {
        Jwt jwt = buildJwt(List.of("admin")); // lowercase

        mockMvc.perform(get("/api/v1/admin/sla-policies")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Unknown role — authenticated but no authority matched
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Unknown role 'SUPERUSER' does not match any @PreAuthorize (403, not 401)")
    void unknown_role_is_authenticated_but_not_authorised() throws Exception {
        Jwt jwt = buildJwt(List.of("SUPERUSER")); // invented role

        mockMvc.perform(get("/api/v1/work-orders")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Unknown role mixed with valid lowercase role still grants no authority")
    void mixed_unknown_and_lowercase_roles_grant_no_authority() throws Exception {
        // Both values are wrong: one is unknown, one is wrong case
        Jwt jwt = buildJwt(List.of("SUPERUSER", "technician"));

        mockMvc.perform(get("/api/v1/work-orders")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Empty roles list — authenticated, no authority
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Token with empty roles list returns 403 on role-guarded endpoint (not 401)")
    void empty_roles_list_produces_403_on_role_guarded_endpoint() throws Exception {
        Jwt jwt = buildJwt(List.of()); // empty list

        mockMvc.perform(get("/api/v1/admin/sla-policies")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Cross-role escalation — token with only CUSTOMER role cannot reach staff-only endpoints
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CUSTOMER role cannot reach inventory endpoint (STAFF-only)")
    void customer_cannot_access_inventory_endpoint() throws Exception {
        Jwt jwt = buildJwt(List.of("CUSTOMER"));

        mockMvc.perform(get("/api/v1/inventory/parts")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("CUSTOMER role cannot reach audit revisions endpoint (ADMIN/MANAGER-only)")
    void customer_cannot_access_revisions_endpoint() throws Exception {
        Jwt jwt = buildJwt(List.of("CUSTOMER"));

        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", TestJwtFactory.WO_001_ID)
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN role cannot reach customer listing endpoint (NO_TECH restriction)")
    void technician_cannot_access_customer_listing() throws Exception {
        Jwt jwt = buildJwt(List.of("TECHNICIAN"));

        mockMvc.perform(get("/api/v1/customers")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Builds a structurally valid JWT (all required claims present) but with the given
     * roles list. Used to test that the role → authority mapping correctly rejects
     * non-canonical role strings.
     */
    private static Jwt buildJwt(List<String> roles) {
        Instant now = Instant.now();
        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", UUID.randomUUID().toString());
        claims.put("iss", "https://test.fieldservice.local");
        claims.put("iat", now);
        claims.put("exp", now.plusSeconds(300));
        claims.put("roles", roles);
        return new Jwt("test-token", now, now.plusSeconds(300),
                Map.of("alg", "RS256", "typ", "JWT"), claims);
    }
}
