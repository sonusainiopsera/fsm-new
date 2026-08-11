package com.fieldservice.fixtures;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RoleAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserFixtures unit tests")
class UserFixturesTest {

    @BeforeEach
    void reset() {
        DeterministicIds.resetSequence();
    }

    @Test
    @DisplayName("Dispatcher builder produces active user with example.org email")
    void dispatcher_defaults() {
        AppUser user = UserFixtures.dispatcher().build();
        assertThat(user.getEmail()).contains("example");
        assertThat(user.isActive()).isTrue();
        assertThat(user.getDisplayName()).isNotBlank();
    }

    @Test
    @DisplayName("Each role builder produces a non-null ID")
    void allRoles_produceDeterministicNonNullIds() {
        assertThat(UserFixtures.admin().build().getId()).isNotNull();
        assertThat(UserFixtures.dispatcher().build().getId()).isNotNull();
        assertThat(UserFixtures.technician().build().getId()).isNotNull();
        assertThat(UserFixtures.manager().build().getId()).isNotNull();
        assertThat(UserFixtures.customer().build().getId()).isNotNull();
    }

    @Test
    @DisplayName("Two identical builds from reset produce the same IDs (determinism)")
    void determinism_twoBuilds_sameId() {
        DeterministicIds.resetSequence();
        AppUser first = UserFixtures.dispatcher().build();

        DeterministicIds.resetSequence();
        AppUser second = UserFixtures.dispatcher().build();

        assertThat(first.getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("buildWithRole produces matching user-id in RoleAssignment")
    void buildWithRole_roleAssignmentLinksToUser() {
        UserFixtures.UserWithRole uwr = UserFixtures.dispatcher().buildWithRole();
        assertThat(uwr.roleAssignment().getUserId()).isEqualTo(uwr.user().getId());
        assertThat(uwr.roleAssignment().getRoleName()).isEqualTo(IdentityRole.DISPATCHER);
        assertThat(uwr.roleAssignment().getGrantedAt()).isEqualTo(DeterministicIds.EPOCH);
    }

    @Test
    @DisplayName("Password hash is a BCrypt cost-12 hash verifiable with TEST_PASSWORD")
    void passwordHash_verifiableWithTestPassword() {
        String hash = UserFixtures.getTestPasswordHash();
        assertThat(hash).startsWith("{bcrypt}");
        // Strip prefix and verify raw hash length (BCrypt = 60 chars)
        String rawHash = hash.substring("{bcrypt}".length());
        assertThat(rawHash).hasSize(60).startsWith("$2a$12$");

        // Verify with BCryptPasswordEncoder directly
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        assertThat(encoder.matches(UserFixtures.TEST_PASSWORD, rawHash)).isTrue();
    }

    @Test
    @DisplayName("Email addresses use reserved example.com or example.org domain")
    void emailAddresses_useReservedDomain() {
        assertEmailDomain(UserFixtures.admin().build());
        assertEmailDomain(UserFixtures.dispatcher().build());
        assertEmailDomain(UserFixtures.technician().build());
        assertEmailDomain(UserFixtures.manager().build());
        assertEmailDomain(UserFixtures.customer().build());
    }

    private void assertEmailDomain(AppUser user) {
        String email = user.getEmail();
        assertThat(email)
                .as("Email must use reserved example domain: %s", email)
                .satisfiesAnyOf(
                        e -> assertThat(e).endsWith("@example.com"),
                        e -> assertThat(e).endsWith("@example.org"),
                        e -> assertThat(e).contains("@example."));
    }

    @Test
    @DisplayName("withEmail override is respected")
    void withEmail_overrideIsApplied() {
        AppUser user = UserFixtures.dispatcher().withEmail("custom.dispatcher@example.org").build();
        assertThat(user.getEmail()).isEqualTo("custom.dispatcher@example.org");
    }

    @Test
    @DisplayName("withoutPassword produces user with null password hash")
    void withoutPassword_nullHash() {
        AppUser user = UserFixtures.dispatcher().withoutPassword().build();
        assertThat(user.getPasswordHash()).isNull();
    }
}
