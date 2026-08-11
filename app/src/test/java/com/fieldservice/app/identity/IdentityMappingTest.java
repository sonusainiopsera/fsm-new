package com.fieldservice.app.identity;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for identity domain entity mappings.
 *
 * <p>No Spring context — tests run purely on class metadata via reflection.
 * Verifies column names, nullability, the UUIDv7 generator, the role-name
 * enumeration, and the Envers @Audited annotations.
 */
class IdentityMappingTest {

    // ---- AppUser ----------------------------------------------------------------

    @Test
    @DisplayName("AppUser is @Audited and maps to app_user table")
    void appUser_auditedAndCorrectTable() {
        assertThat(AppUser.class.isAnnotationPresent(Audited.class)).isTrue();
        assertThat(AppUser.class.isAnnotationPresent(Entity.class)).isTrue();
        Table table = AppUser.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("app_user");
    }

    @Test
    @DisplayName("AppUser.id is @Id field")
    void appUser_idField() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("id");
        assertThat(f.isAnnotationPresent(Id.class)).isTrue();
    }

    @Test
    @DisplayName("AppUser.passwordHash is @NotAudited (Restricted classification)")
    void appUser_passwordHashNotAudited() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("passwordHash");
        assertThat(f.isAnnotationPresent(NotAudited.class)).isTrue();
        Column col = f.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("password_hash");
        assertThat(col.length()).isGreaterThanOrEqualTo(72);
    }

    @Test
    @DisplayName("AppUser.email maps to email column (not null)")
    void appUser_emailColumn() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("email");
        Column col = f.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.nullable()).isFalse();
    }

    @Test
    @DisplayName("AppUser.displayName maps to display_name column (nullable)")
    void appUser_displayNameColumn() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("displayName");
        Column col = f.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("display_name");
        assertThat(col.nullable()).isTrue();
    }

    @Test
    @DisplayName("AppUser.externalSubject maps to external_subject column (nullable)")
    void appUser_externalSubjectColumn() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("externalSubject");
        Column col = f.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("external_subject");
        assertThat(col.nullable()).isTrue();
    }

    @Test
    @DisplayName("AppUser.version is @Version for optimistic locking")
    void appUser_versionField() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("version");
        assertThat(f.isAnnotationPresent(Version.class)).isTrue();
    }

    @Test
    @DisplayName("AppUser.create() generates a UUIDv7 identifier (version bit = 7)")
    void appUser_createGeneratesUuidV7() {
        AppUser u = AppUser.create("user@example.local", "Test User");
        assertThat(u.getId()).isNotNull();
        int version = (int) ((u.getId().getMostSignificantBits() >> 12) & 0xFL);
        assertThat(version).isEqualTo(7);
    }

    @Test
    @DisplayName("AppUser.create() normalises email to lowercase and strips whitespace")
    void appUser_normaliseEmail() {
        AppUser u = AppUser.create("  Alice@Example.COM  ", "Alice");
        assertThat(u.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("AppUser.createWithPassword() sets passwordHash and is nullable by default")
    void appUser_createWithPassword() {
        String hash = "$2a$10$" + "x".repeat(53); // 60-char BCrypt pattern
        AppUser u = AppUser.createWithPassword("u@example.local", "User", hash);
        assertThat(u.getPasswordHash()).isEqualTo(hash);
    }

    @Test
    @DisplayName("AppUser created with no password has null passwordHash")
    void appUser_nullablePasswordHash() {
        AppUser u = AppUser.create("nopw@example.local", "No PW");
        assertThat(u.getPasswordHash()).isNull();
    }

    // ---- RoleAssignment ---------------------------------------------------------

    @Test
    @DisplayName("RoleAssignment is @Audited and maps to role_assignment table")
    void roleAssignment_auditedAndCorrectTable() {
        assertThat(RoleAssignment.class.isAnnotationPresent(Audited.class)).isTrue();
        Table table = RoleAssignment.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("role_assignment");
    }

    @Test
    @DisplayName("RoleAssignment.roleName uses @Enumerated(EnumType.STRING)")
    void roleAssignment_roleNameEnumString() throws NoSuchFieldException {
        Field f = RoleAssignment.class.getDeclaredField("roleName");
        Enumerated e = f.getAnnotation(Enumerated.class);
        assertThat(e).isNotNull();
        assertThat(e.value()).isEqualTo(EnumType.STRING);
        Column col = f.getAnnotation(Column.class);
        assertThat(col.name()).isEqualTo("role_name");
    }

    @Test
    @DisplayName("AppRole enum contains exactly five ratified roles")
    void appRole_fiveRatifiedRoles() {
        AppRole[] roles = AppRole.values();
        assertThat(roles).containsExactlyInAnyOrder(
                AppRole.ADMIN, AppRole.DISPATCHER, AppRole.TECHNICIAN,
                AppRole.MANAGER, AppRole.CUSTOMER);
    }

    @Test
    @DisplayName("RoleAssignment.grant() generates UUIDv7 and sets grantedAt")
    void roleAssignment_grantFactory() {
        AppUser u = AppUser.create("admin@example.local", "Admin");
        RoleAssignment ra = RoleAssignment.grant(u, AppRole.ADMIN, "SYSTEM");
        assertThat(ra.getId()).isNotNull();
        int version = (int) ((ra.getId().getMostSignificantBits() >> 12) & 0xFL);
        assertThat(version).isEqualTo(7);
        assertThat(ra.getRoleName()).isEqualTo(AppRole.ADMIN);
        assertThat(ra.getGrantedBy()).isEqualTo("SYSTEM");
        assertThat(ra.getGrantedAt()).isNotNull();
    }

    // ---- RefreshTokenFamily -----------------------------------------------------

    @Test
    @DisplayName("RefreshTokenFamily maps to refresh_token_family table")
    void refreshTokenFamily_correctTable() {
        Table table = RefreshTokenFamily.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("refresh_token_family");
    }

    @Test
    @DisplayName("RefreshTokenFamily.open() creates a non-revoked family with UUIDv7")
    void refreshTokenFamily_openFactory() {
        AppUser u = AppUser.create("u@example.local", "U");
        RefreshTokenFamily f = RefreshTokenFamily.open(u);
        assertThat(f.getId()).isNotNull();
        assertThat(f.isRevoked()).isFalse();
        assertThat(f.getCreatedAt()).isNotNull();
    }

    // ---- RefreshToken -----------------------------------------------------------

    @Test
    @DisplayName("RefreshToken maps to refresh_token table")
    void refreshToken_correctTable() {
        Table table = RefreshToken.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("refresh_token");
    }

    @Test
    @DisplayName("RefreshToken.tokenHash column is length 64 (SHA-256 hex)")
    void refreshToken_tokenHashLength() throws NoSuchFieldException {
        Field f = RefreshToken.class.getDeclaredField("tokenHash");
        Column col = f.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("token_hash");
        assertThat(col.length()).isEqualTo(64);
    }

    @Test
    @DisplayName("RefreshToken.issue() sets issuedAt and expiresAt")
    void refreshToken_issueFactory() {
        AppUser u = AppUser.create("u@example.local", "U");
        RefreshTokenFamily fam = RefreshTokenFamily.open(u);
        String hash = "a".repeat(64);
        Instant expires = Instant.now().plusSeconds(3600);
        RefreshToken rt = RefreshToken.issue(fam, hash, expires);
        assertThat(rt.getId()).isNotNull();
        assertThat(rt.getTokenHash()).isEqualTo(hash);
        assertThat(rt.getExpiresAt()).isEqualTo(expires);
        assertThat(rt.isConsumed()).isFalse();
        assertThat(rt.isRevoked()).isFalse();
    }
}
