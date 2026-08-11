package com.fieldservice.fixtures;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RoleAssignment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.UUID;

/**
 * Object-mother for {@link AppUser} and {@link RoleAssignment} test fixtures.
 *
 * <p>All e-mail addresses use the IANA-reserved {@code example.com} or {@code example.org}
 * domains and all phone numbers use the reserved {@code +1 555-555-0xxx} range to ensure
 * no real personal data enters the repository (fixture-hygiene rule, WO-201).
 *
 * <p>BCrypt hashes are computed at cost 12 (matching {@code PasswordEncoderConfig}) and
 * stored in {@code {bcrypt}$2a$12$…} format compatible with {@code DelegatingPasswordEncoder}.
 * The computation happens once per JVM via a lazy initializer; tests that need
 * authentication should call {@link #getTestPasswordHash()} rather than re-encoding.
 *
 * <p>Fixtures do not depend on Spring context: only Spring-Security's {@link BCryptPasswordEncoder}
 * utility class is used, which has no DI requirements.
 */
public final class UserFixtures {

    /** Documented plaintext password for all fixture users. Never commit outside test sources. */
    public static final String TEST_PASSWORD = "TestFixture@1234!";

    private static volatile String testPasswordHash;

    private UserFixtures() {}

    /**
     * Returns the BCrypt cost-12 hash for {@link #TEST_PASSWORD} in {@code DelegatingPasswordEncoder}
     * format ({@code {bcrypt}$2a$12$…}). Computed once per JVM and cached.
     */
    public static String getTestPasswordHash() {
        if (testPasswordHash == null) {
            synchronized (UserFixtures.class) {
                if (testPasswordHash == null) {
                    testPasswordHash = "{bcrypt}" + new BCryptPasswordEncoder(12).encode(TEST_PASSWORD);
                }
            }
        }
        return testPasswordHash;
    }

    // -----------------------------------------------------------------------
    // Per-role builder factories
    // -----------------------------------------------------------------------

    public static Builder admin() {
        return new Builder(IdentityRole.ADMIN)
                .withEmail("admin@example.com")
                .withDisplayName("Fixture Admin")
                .withActive(true);
    }

    public static Builder dispatcher() {
        return new Builder(IdentityRole.DISPATCHER)
                .withEmail("dispatcher@example.org")
                .withDisplayName("Fixture Dispatcher")
                .withActive(true);
    }

    public static Builder technician() {
        return new Builder(IdentityRole.TECHNICIAN)
                .withEmail("technician@example.com")
                .withDisplayName("Fixture Technician")
                .withActive(true);
    }

    public static Builder manager() {
        return new Builder(IdentityRole.MANAGER)
                .withEmail("manager@example.org")
                .withDisplayName("Fixture Manager")
                .withActive(true);
    }

    public static Builder customer() {
        return new Builder(IdentityRole.CUSTOMER)
                .withEmail("customer@example.com")
                .withDisplayName("Fixture Customer")
                .withActive(true);
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static final class Builder {

        private final IdentityRole role;
        private UUID id = DeterministicIds.nextId();
        private String email;
        private String displayName;
        private boolean active = true;
        private boolean includePassword = true;

        private Builder(IdentityRole role) {
            this.role = role;
        }

        public Builder withId(UUID id)                 { this.id = id;                         return this; }
        public Builder withEmail(String email)         { this.email = email;                   return this; }
        public Builder withDisplayName(String name)    { this.displayName = name;              return this; }
        public Builder withActive(boolean active)      { this.active = active;                 return this; }
        public Builder withoutPassword()               { this.includePassword = false;         return this; }

        /** Builds an {@link AppUser} populated with deterministic defaults. */
        public AppUser build() {
            AppUser user = new AppUser();
            user.setId(id);
            user.setEmail(email);
            user.setDisplayName(displayName);
            user.setActive(active);
            if (includePassword) {
                user.setPasswordHash(getTestPasswordHash());
            }
            return user;
        }

        /**
         * Builds the user and an associated {@link RoleAssignment} for {@link #role}.
         * Returns a {@link UserWithRole} carrier so callers can persist both objects.
         */
        public UserWithRole buildWithRole() {
            AppUser user = build();
            RoleAssignment grant = new RoleAssignment(
                    user.getId(),
                    role,
                    DeterministicIds.EPOCH,
                    null);
            return new UserWithRole(user, grant);
        }
    }

    // -----------------------------------------------------------------------
    // Value carrier
    // -----------------------------------------------------------------------

    /** Carries an {@link AppUser} and its single {@link RoleAssignment} together. */
    public record UserWithRole(AppUser user, RoleAssignment roleAssignment) {}
}
