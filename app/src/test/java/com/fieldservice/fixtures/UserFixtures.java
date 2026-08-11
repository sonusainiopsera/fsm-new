package com.fieldservice.fixtures;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.RoleAssignment;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

/**
 * Object-mother builders for {@link AppUser} and {@link RoleAssignment}.
 *
 * <p>Emails use the reserved {@code example.local} domain so no value
 * resembles a real address.  Phone numbers use the reserved {@code 555} prefix.
 * Passwords are never stored in plain text; only {@link #BCRYPT_HASH_COST12}
 * appears and it is the standard BCrypt cost-12 hash of {@link #TEST_PASSWORD}.
 */
public final class UserFixtures {

    /** Plaintext password used in all test fixtures. Never stored as-is. */
    public static final String TEST_PASSWORD = "TestPassword123!";

    /**
     * BCrypt cost-12 hash of {@link #TEST_PASSWORD}.
     * Pre-computed so tests do not pay the BCrypt cost on every run.
     */
    public static final String BCRYPT_HASH_COST12 =
            "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC";

    private UserFixtures() {}

    // ---- Builder -------------------------------------------------------------

    public static final class Builder {

        private UUID    id           = DeterministicIds.next();
        private String  email;
        private String  displayName;
        private String  passwordHash = BCRYPT_HASH_COST12;
        private boolean active       = true;

        private Builder(String email, String displayName) {
            this.email       = email;
            this.displayName = displayName;
        }

        public Builder withId(UUID id)               { this.id = id;               return this; }
        public Builder withEmail(String e)           { this.email = e;             return this; }
        public Builder withDisplayName(String name)  { this.displayName = name;    return this; }
        public Builder withPasswordHash(String hash) { this.passwordHash = hash;   return this; }
        public Builder inactive()                    { this.active = false;        return this; }

        public AppUser build() {
            AppUser user = AppUser.createWithPassword(email, displayName, passwordHash);
            setField(AppUser.class, user, "id", id);
            if (!active) user.deactivate();
            return user;
        }
    }

    // ---- Role-assignment builder ---------------------------------------------

    public static final class RoleAssignmentBuilder {

        private UUID    id        = DeterministicIds.next();
        private AppUser user;
        private AppRole role;

        private RoleAssignmentBuilder(AppUser user, AppRole role) {
            this.user = user;
            this.role = role;
        }

        public RoleAssignmentBuilder withId(UUID id) { this.id = id; return this; }

        public RoleAssignment build() {
            RoleAssignment ra = RoleAssignment.grant(user, role, "fixture");
            setField(RoleAssignment.class, ra, "id", id);
            setField(RoleAssignment.class, ra, "grantedAt", DeterministicIds.FIXED_INSTANT);
            return ra;
        }
    }

    // ---- Static factories ----------------------------------------------------

    public static Builder admin() {
        return new Builder("admin@example.local", "Admin User");
    }

    public static Builder dispatcher() {
        return new Builder("dispatcher@example.local", "Dispatcher User");
    }

    public static Builder technician() {
        return new Builder("technician@example.local", "Technician User");
    }

    public static Builder manager() {
        return new Builder("manager@example.local", "Manager User");
    }

    public static Builder customer() {
        return new Builder("customer@example.local", "Customer User");
    }

    public static Builder inactive() {
        return new Builder("inactive@example.local", "Inactive User").inactive();
    }

    public static Builder withEmail(String email, String displayName) {
        return new Builder(email, displayName);
    }

    public static RoleAssignmentBuilder roleFor(AppUser user, AppRole role) {
        return new RoleAssignmentBuilder(user, role);
    }

    // ---- Reflection helper ---------------------------------------------------

    static void setField(Class<?> cls, Object target, String name, Object value) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Fixture reflection failed: " + cls.getSimpleName() + "." + name, e);
        }
    }
}
