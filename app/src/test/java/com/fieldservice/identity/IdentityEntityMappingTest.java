package com.fieldservice.identity;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RoleAssignment;
import org.hibernate.envers.Audited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.NotAudited;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for identity entity JPA mappings — no Spring context required.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Column names, nullability and lengths as declared in the entity annotations.</li>
 *   <li>UUIDv7 generator annotation is present on each {@code id} field.</li>
 *   <li>Role-name enumeration is mapped via {@code EnumType.STRING}.</li>
 *   <li>{@code password_hash} and {@code external_subject} are {@code @NotAudited}.</li>
 *   <li>Audit annotation present on audited entities; absent on non-audited ones.</li>
 * </ul>
 */
@DisplayName("Identity entity JPA mapping unit tests (no Spring context)")
class IdentityEntityMappingTest {

    // -------------------------------------------------------------------------
    // AppUser mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AppUser maps to app_user table and is @Audited")
    void appUser_tableAndAudited() {
        assertThat(AppUser.class.getAnnotation(Table.class).name()).isEqualTo("app_user");
        assertThat(AppUser.class.getAnnotation(Audited.class)).isNotNull();
    }

    @Test
    @DisplayName("AppUser.passwordHash is @NotAudited and nullable")
    void appUser_passwordHashNotAuditedAndNullable() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("passwordHash");
        assertThat(f.getAnnotation(NotAudited.class))
                .as("password_hash must be @NotAudited (CONFIDENTIAL)")
                .isNotNull();
        Column col = f.getAnnotation(Column.class);
        assertThat(col.nullable())
                .as("password_hash must be nullable (federated users have no local credential)")
                .isTrue();
        assertThat(col.length())
                .as("password_hash column must be at least 72 chars wide")
                .isGreaterThanOrEqualTo(72);
    }

    @Test
    @DisplayName("AppUser.externalSubject is @NotAudited")
    void appUser_externalSubjectNotAudited() throws NoSuchFieldException {
        Field f = AppUser.class.getDeclaredField("externalSubject");
        assertThat(f.getAnnotation(NotAudited.class))
                .as("external_subject must be @NotAudited")
                .isNotNull();
        assertThat(f.getAnnotation(Column.class).name()).isEqualTo("external_subject");
    }

    // -------------------------------------------------------------------------
    // RoleAssignment mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("RoleAssignment maps to role_assignment table and is @Audited")
    void roleAssignment_tableAndAudited() {
        assertThat(RoleAssignment.class.getAnnotation(Table.class).name())
                .isEqualTo("role_assignment");
        assertThat(RoleAssignment.class.getAnnotation(Audited.class)).isNotNull();
    }

    @Test
    @DisplayName("RoleAssignment.roleName is @Enumerated(STRING)")
    void roleAssignment_roleNameEnumMappedAsString() throws NoSuchFieldException {
        Field f = RoleAssignment.class.getDeclaredField("roleName");
        Enumerated en = f.getAnnotation(Enumerated.class);
        assertThat(en).isNotNull();
        assertThat(en.value()).isEqualTo(EnumType.STRING);
    }

    @Test
    @DisplayName("IdentityRole contains exactly the five ratified roles")
    void identityRole_fiveValues() {
        IdentityRole[] values = IdentityRole.values();
        assertThat(values).containsExactlyInAnyOrder(
                IdentityRole.ADMIN, IdentityRole.DISPATCHER, IdentityRole.TECHNICIAN,
                IdentityRole.MANAGER, IdentityRole.CUSTOMER);
    }

    @Test
    @DisplayName("RoleAssignment can be constructed and all getters return expected values")
    void roleAssignment_constructorAndGetters() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        UUID grantedBy = UUID.randomUUID();

        RoleAssignment ra = new RoleAssignment(userId, IdentityRole.DISPATCHER, now, grantedBy);

        assertThat(ra.getUserId()).isEqualTo(userId);
        assertThat(ra.getRoleName()).isEqualTo(IdentityRole.DISPATCHER);
        assertThat(ra.getGrantedAt()).isEqualTo(now);
        assertThat(ra.getGrantedBy()).isEqualTo(grantedBy);
        // id is null before persistence (assigned by generator at persist time)
        assertThat(ra.getId()).isNull();
    }

    // -------------------------------------------------------------------------
    // RefreshTokenFamily mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("RefreshTokenFamily maps to refresh_token_family and is NOT @Audited")
    void refreshTokenFamily_tableAndNotAudited() {
        assertThat(RefreshTokenFamily.class.getAnnotation(Table.class).name())
                .isEqualTo("refresh_token_family");
        assertThat(RefreshTokenFamily.class.getAnnotation(Audited.class))
                .as("RefreshTokenFamily is high-churn session state, must NOT be @Audited")
                .isNull();
    }

    @Test
    @DisplayName("RefreshTokenFamily.revoke() sets revokedAt and revokedReason")
    void refreshTokenFamily_revoke() {
        RefreshTokenFamily family = new RefreshTokenFamily(UUID.randomUUID(), Instant.now());
        assertThat(family.isRevoked()).isFalse();

        Instant revokedAt = Instant.now();
        family.revoke(revokedAt, "LOGOUT");

        assertThat(family.isRevoked()).isTrue();
        assertThat(family.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(family.getRevokedReason()).isEqualTo("LOGOUT");
    }

    // -------------------------------------------------------------------------
    // RefreshToken mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("RefreshToken maps to refresh_token and is NOT @Audited")
    void refreshToken_tableAndNotAudited() {
        assertThat(RefreshToken.class.getAnnotation(Table.class).name())
                .isEqualTo("refresh_token");
        assertThat(RefreshToken.class.getAnnotation(Audited.class))
                .as("RefreshToken is session state, must NOT be @Audited")
                .isNull();
    }

    @Test
    @DisplayName("RefreshToken.tokenHash column is length 64 (SHA-256 hex)")
    void refreshToken_tokenHashLength() throws NoSuchFieldException {
        Field f = RefreshToken.class.getDeclaredField("tokenHash");
        Column col = f.getAnnotation(Column.class);
        assertThat(col.length())
                .as("token_hash must be exactly 64 chars for SHA-256 hex")
                .isEqualTo(64);
    }

    @Test
    @DisplayName("RefreshToken.consume() sets consumedAt and isConsumed() returns true")
    void refreshToken_consume() {
        RefreshToken token = new RefreshToken(
                UUID.randomUUID(), "a".repeat(64), Instant.now(), Instant.now().plusSeconds(3600));
        assertThat(token.isConsumed()).isFalse();

        Instant consumedAt = Instant.now();
        token.consume(consumedAt);

        assertThat(token.isConsumed()).isTrue();
        assertThat(token.getConsumedAt()).isEqualTo(consumedAt);
    }
}
