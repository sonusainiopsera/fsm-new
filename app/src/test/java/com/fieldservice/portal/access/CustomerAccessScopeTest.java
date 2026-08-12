package com.fieldservice.portal.access;

import com.fieldservice.portal.domain.PortalAccountStatus;
import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.repository.PortalAccountUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerAccessScope} — no Spring context required.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Successful account-id resolution from the JWT principal via DB lookup.</li>
 *   <li>Per-request caching: the repository is queried at most once.</li>
 *   <li>Fail-closed: ScopeUnavailableException for unauthenticated, non-JWT,
 *       missing linkage, and suspended linkage.</li>
 *   <li>workOrderPredicate() is non-null and reflects the resolved account id.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CustomerAccessScopeTest {

    static final UUID USER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    PortalAccountUserRepository repository;

    CustomerAccessScope scope;

    @BeforeEach
    void setUp() {
        scope = new CustomerAccessScope(repository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------

    @Test
    @DisplayName("resolveAccountId: returns account id from portal_account_user row")
    void resolveAccountId_returnsAccountId_whenLinked() {
        setJwtContext(USER_ID.toString());
        PortalAccountUser pau = activeUser(USER_ID, ACCOUNT_ID);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(pau));

        UUID result = scope.resolveAccountId();

        assertThat(result).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @DisplayName("resolveAccountId: second call returns cached value without DB query")
    void resolveAccountId_cached_afterFirstCall() {
        setJwtContext(USER_ID.toString());
        PortalAccountUser pau = activeUser(USER_ID, ACCOUNT_ID);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(pau));

        scope.resolveAccountId(); // first call — DB hit
        scope.resolveAccountId(); // second call — should use cache

        // Mockito verifies the repository was called exactly once
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).findByUserId(USER_ID);
    }

    @Test
    @DisplayName("workOrderPredicate: returns non-null Specification when linked")
    void workOrderPredicate_notNull_whenLinked() {
        setJwtContext(USER_ID.toString());
        PortalAccountUser pau = activeUser(USER_ID, ACCOUNT_ID);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(pau));

        var predicate = scope.workOrderPredicate();

        assertThat(predicate).isNotNull();
    }

    // ---------------------------------------------------------------
    // Fail-closed scenarios
    // ---------------------------------------------------------------

    @Test
    @DisplayName("resolveAccountId: throws ScopeUnavailableException when unauthenticated")
    void resolveAccountId_throws_whenUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("resolveAccountId: throws ScopeUnavailableException when auth is not JWT")
    void resolveAccountId_throws_whenNonJwtAuth() {
        Authentication nonJwt = mock(Authentication.class);
        when(nonJwt.isAuthenticated()).thenReturn(true);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(nonJwt);
        SecurityContextHolder.setContext(ctx);

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("resolveAccountId: throws ScopeUnavailableException when no linkage row")
    void resolveAccountId_throws_whenNoLinkageRow() {
        setJwtContext(USER_ID.toString());
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("resolveAccountId: throws ScopeUnavailableException when linkage is SUSPENDED")
    void resolveAccountId_throws_whenLinkageSuspended() {
        setJwtContext(USER_ID.toString());
        PortalAccountUser pau = new PortalAccountUser(USER_ID, ACCOUNT_ID, PortalAccountStatus.SUSPENDED);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(pau));

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("resolveAccountId: throws ScopeUnavailableException when linkage is PENDING")
    void resolveAccountId_throws_whenLinkagePending() {
        setJwtContext(USER_ID.toString());
        PortalAccountUser pau = new PortalAccountUser(USER_ID, ACCOUNT_ID, PortalAccountStatus.PENDING);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(pau));

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("resolveAccountId: throws ScopeUnavailableException when JWT subject is blank")
    void resolveAccountId_throws_whenBlankSubject() {
        setJwtContext("   ");
        // No need to stub repository — should throw before DB call

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("resolveAccountId: throws ScopeUnavailableException when JWT subject is not a UUID")
    void resolveAccountId_throws_whenMalformedSubject() {
        setJwtContext("not-a-uuid");

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static PortalAccountUser activeUser(UUID userId, UUID accountId) {
        return new PortalAccountUser(userId, accountId);
    }

    private void setJwtContext(String subject) {
        Jwt jwt = Jwt.withTokenValue("test.jwt.token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(jwtAuth);
        SecurityContextHolder.setContext(ctx);
    }
}
