package com.fieldservice.portal.access;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.domain.PortalAccountUserRepository;
import com.fieldservice.portal.domain.PortalAccountUserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerAccessScope} (WO-169, AC-4, AC-9).
 *
 * <p>No Spring context — exercises principal-to-account resolution in isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerAccessScope unit tests")
class CustomerAccessScopeTest {

    @Mock
    private AccessScopeResolver scopeResolver;

    @Mock
    private PortalAccountUserRepository repository;

    private CustomerAccessScope scope;

    private static final UUID USER_ID    = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000016");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        scope = new CustomerAccessScope(scopeResolver, repository);
    }

    @Test
    @DisplayName("AC-4: resolves account_id from portal_account_user for ACTIVE linkage")
    void resolveAccountId_activeLinkage_returnsAccountId() {
        givenActiveScope(USER_ID);
        givenActiveLinkage(USER_ID, ACCOUNT_ID);

        UUID resolved = scope.resolveAccountId();

        assertThat(resolved).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @DisplayName("AC-4: caches account_id within the request (repository called once)")
    void resolveAccountId_cached_repositoryCalledOnce() {
        givenActiveScope(USER_ID);
        givenActiveLinkage(USER_ID, ACCOUNT_ID);

        scope.resolveAccountId();
        scope.resolveAccountId(); // second call should not hit repository

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).findByUserId(USER_ID);
    }

    @Test
    @DisplayName("AC-4 fail-closed: no linkage row → ScopeUnavailableException")
    void resolveAccountId_noLinkageRow_throwsScopeUnavailable() {
        givenActiveScope(USER_ID);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("AC-4 fail-closed: PENDING linkage → ScopeUnavailableException")
    void resolveAccountId_pendingStatus_throwsScopeUnavailable() {
        givenActiveScope(USER_ID);
        givenLinkageWithStatus(USER_ID, ACCOUNT_ID, PortalAccountUserStatus.PENDING);

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("AC-4 fail-closed: SUSPENDED linkage → ScopeUnavailableException")
    void resolveAccountId_suspendedStatus_throwsScopeUnavailable() {
        givenActiveScope(USER_ID);
        givenLinkageWithStatus(USER_ID, ACCOUNT_ID, PortalAccountUserStatus.SUSPENDED);

        assertThatThrownBy(() -> scope.resolveAccountId())
                .isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    @DisplayName("AC-5: workOrderScopeSpec() returns a non-null Specification")
    void workOrderScopeSpec_returnsNonNullSpecification() {
        givenActiveScope(USER_ID);
        givenActiveLinkage(USER_ID, ACCOUNT_ID);

        var spec = scope.workOrderScopeSpec();

        assertThat(spec).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void givenActiveScope(UUID userId) {
        AccessScope accessScope = new AccessScope(userId, Set.of("ROLE_CUSTOMER"), null, Set.of());
        when(scopeResolver.resolve()).thenReturn(accessScope);
    }

    private void givenActiveLinkage(UUID userId, UUID accountId) {
        PortalAccountUser linkage = new PortalAccountUser(userId, accountId);
        linkage.activate(Instant.now());
        when(repository.findByUserId(userId)).thenReturn(Optional.of(linkage));
    }

    private void givenLinkageWithStatus(UUID userId, UUID accountId, PortalAccountUserStatus status) {
        PortalAccountUser linkage = new PortalAccountUser(userId, accountId);
        if (status == PortalAccountUserStatus.SUSPENDED) {
            linkage.activate(Instant.now());
            linkage.suspend();
        }
        // PENDING is the default, no change needed
        when(repository.findByUserId(userId)).thenReturn(Optional.of(linkage));
    }
}
