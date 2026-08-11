package com.fieldservice.platform.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link AccessScope} record — invariants, role checks, and defensive copies.
 */
class AccessScopeTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TECH_ID = UUID.randomUUID();
    private static final UUID ACCT_ID = UUID.randomUUID();

    @Test
    void dispatcherIsPrivileged() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.DISPATCHER), null, Set.of());
        assertThat(scope.isPrivileged()).isTrue();
        assertThat(scope.isTechnician()).isFalse();
        assertThat(scope.isCustomer()).isFalse();
    }

    @Test
    void adminIsPrivileged() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.ADMIN), null, Set.of());
        assertThat(scope.isPrivileged()).isTrue();
    }

    @Test
    void managerIsPrivileged() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.MANAGER), null, Set.of());
        assertThat(scope.isPrivileged()).isTrue();
    }

    @Test
    void technicianIsNotPrivileged() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.TECHNICIAN), TECH_ID, Set.of());
        assertThat(scope.isPrivileged()).isFalse();
        assertThat(scope.isTechnician()).isTrue();
    }

    @Test
    void customerIsNotPrivileged() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.CUSTOMER), null, Set.of(ACCT_ID));
        assertThat(scope.isPrivileged()).isFalse();
        assertThat(scope.isCustomer()).isTrue();
    }

    @Test
    void hasRoleAcceptsBothPrefixedAndBareForms() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.DISPATCHER), null, Set.of());
        assertThat(scope.hasRole("DISPATCHER")).isTrue();
        assertThat(scope.hasRole("ROLE_DISPATCHER")).isTrue();
        assertThat(scope.hasRole("ADMIN")).isFalse();
    }

    @Test
    void rolesAreImmutableDefensiveCopy() {
        Set<String> mutableRoles = new java.util.HashSet<>();
        mutableRoles.add(Role.DISPATCHER);
        AccessScope scope = new AccessScope(USER_ID, mutableRoles, null, Set.of());
        mutableRoles.add(Role.ADMIN); // mutate original
        assertThat(scope.roles()).doesNotContain(Role.ADMIN);
    }

    @Test
    void customerAccountIdsAreImmutableDefensiveCopy() {
        Set<UUID> mutableIds = new java.util.HashSet<>();
        mutableIds.add(ACCT_ID);
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.CUSTOMER), null, mutableIds);
        UUID extra = UUID.randomUUID();
        mutableIds.add(extra); // mutate original
        assertThat(scope.customerAccountIds()).doesNotContain(extra);
    }

    @Test
    void throwsWhenUserIdIsNull() {
        assertThatThrownBy(() -> new AccessScope(null, Set.of(Role.DISPATCHER), null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void throwsWhenRolesIsNull() {
        assertThatThrownBy(() -> new AccessScope(USER_ID, null, null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void throwsWhenCustomerAccountIdsIsNull() {
        assertThatThrownBy(() -> new AccessScope(USER_ID, Set.of(Role.CUSTOMER), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void multiAccountCustomer_hasAllAccounts() {
        UUID acct2 = UUID.randomUUID();
        AccessScope scope = new AccessScope(
                USER_ID, Set.of(Role.CUSTOMER), null, Set.of(ACCT_ID, acct2));
        assertThat(scope.customerAccountIds()).containsExactlyInAnyOrder(ACCT_ID, acct2);
    }
}
