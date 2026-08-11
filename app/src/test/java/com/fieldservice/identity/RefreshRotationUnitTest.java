package com.fieldservice.identity;

import com.fieldservice.identity.application.RefreshTokenService;
import com.fieldservice.identity.application.SecurityEventPublisher;
import com.fieldservice.identity.application.TokenIssuer;
import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.platform.api.exception.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefreshTokenService} covering all rotation branches.
 *
 * <p>No Spring context, no database — all dependencies are Mockito mocks with a
 * fixed {@link Clock} for deterministic expiry checks.
 */
class RefreshRotationUnitTest {

    // A valid 43-char base64url handle (32 bytes of zeros, base64url without padding)
    private static final String VALID_HANDLE =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private RefreshTokenRepository       tokenRepo;
    private RefreshTokenFamilyRepository familyRepo;
    private RoleAssignmentRepository     roleRepo;
    private TokenIssuer                  tokenIssuer;
    private SecurityEventPublisher       securityPublisher;
    private Clock                        fixedClock;
    private RefreshTokenService          service;

    private Instant now;

    @BeforeEach
    void setUp() {
        tokenRepo         = mock(RefreshTokenRepository.class);
        familyRepo        = mock(RefreshTokenFamilyRepository.class);
        roleRepo          = mock(RoleAssignmentRepository.class);
        tokenIssuer       = mock(TokenIssuer.class);
        securityPublisher = mock(SecurityEventPublisher.class);
        now               = Instant.parse("2026-01-01T12:00:00Z");
        fixedClock        = Clock.fixed(now, ZoneOffset.UTC);

        service = new RefreshTokenService(
                tokenRepo, familyRepo, roleRepo,
                tokenIssuer, securityPublisher, fixedClock);
    }

    // ---- Helpers ---------------------------------------------------------------

    private AppUser activeUser() {
        return AppUser.createWithPassword("user@example.com", "Test User",
                "$2a$12$dummy");
    }

    private RefreshTokenFamily activeFamily(AppUser user, Instant expiresAt) {
        return RefreshTokenFamily.open(user, expiresAt);
    }

    private RefreshToken unconsumedToken(RefreshTokenFamily family, String tokenHash) {
        return RefreshToken.issue(family, tokenHash, family.getExpiresAt());
    }

    private RoleAssignment adminGrant(AppUser user) {
        RoleAssignment r = mock(RoleAssignment.class);
        when(r.getRoleName()).thenReturn(AppRole.ADMIN);
        return r;
    }

    private TokenIssuer.TokenBundle newBundle() {
        return new TokenIssuer.TokenBundle(
                "eyJ.new.jwt",
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBb", // 43-char base64url
                now.plusSeconds(900));
    }

    // ---- Valid rotation --------------------------------------------------------

    @Nested
    @DisplayName("ValidRotation")
    class ValidRotation {

        @Test
        @DisplayName("returns new tokens and persists rotated token under the same family")
        void valid_rotation_issues_new_tokens() {
            AppUser user = activeUser();
            Instant expiresAt = now.plusSeconds(3600);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken token = unconsumedToken(family, hash);
            TokenIssuer.TokenBundle bundle = newBundle();

            when(tokenRepo.consumeToken(hash)).thenReturn(1);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.of(token));
            when(roleRepo.findByUserId(user.getId())).thenReturn(List.of(adminGrant(user)));
            when(tokenIssuer.issue(eq(user), anyList())).thenReturn(bundle);

            RefreshTokenService.RotationResult result =
                    service.rotate(VALID_HANDLE, "10.0.0.1", "TestAgent/1.0");

            assertThat(result.tokens().accessToken()).isEqualTo("eyJ.new.jwt");

            // New token persisted under same family with original expiry
            ArgumentCaptor<RefreshToken> newTokenCaptor =
                    ArgumentCaptor.forClass(RefreshToken.class);
            verify(tokenRepo).save(newTokenCaptor.capture());
            assertThat(newTokenCaptor.getValue().getFamily()).isSameAs(family);
            assertThat(newTokenCaptor.getValue().getExpiresAt()).isEqualTo(expiresAt);

            // Rotation success event published
            verify(securityPublisher).publishRotationSuccess(eq(family), eq(user.getId()), anyString());
        }

        @Test
        @DisplayName("new token hash differs from original (handle was rotated)")
        void rotated_token_has_different_hash() {
            AppUser user = activeUser();
            Instant expiresAt = now.plusSeconds(3600);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            String oldHash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken token = unconsumedToken(family, oldHash);
            TokenIssuer.TokenBundle bundle = newBundle();

            when(tokenRepo.consumeToken(oldHash)).thenReturn(1);
            when(tokenRepo.findByTokenHash(oldHash)).thenReturn(Optional.of(token));
            when(roleRepo.findByUserId(user.getId())).thenReturn(List.of(adminGrant(user)));
            when(tokenIssuer.issue(any(), anyList())).thenReturn(bundle);

            service.rotate(VALID_HANDLE, "127.0.0.1", "");

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(tokenRepo).save(captor.capture());
            String newHash = RefreshTokenService.sha256Hex(bundle.refreshHandle());
            assertThat(captor.getValue().getTokenHash()).isEqualTo(newHash);
            assertThat(captor.getValue().getTokenHash()).isNotEqualTo(oldHash);
        }
    }

    // ---- Consumed handle reuse -------------------------------------------------

    @Nested
    @DisplayName("ConsumedHandleReuse")
    class ConsumedHandleReuse {

        @Test
        @DisplayName("0 rows affected with consumed token revokes family and publishes event")
        void consumed_reuse_revokes_family() {
            AppUser user = activeUser();
            Instant expiresAt = now.plusSeconds(3600);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken consumedToken = unconsumedToken(family, hash);
            consumedToken.consume(); // mark consumed

            when(tokenRepo.consumeToken(hash)).thenReturn(0);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.of(consumedToken));
            when(tokenRepo.findByFamilyId(family.getId())).thenReturn(List.of(consumedToken));

            assertThatThrownBy(() -> service.rotate(VALID_HANDLE, "1.2.3.4", "MaliciousClient"))
                    .isInstanceOf(InvalidCredentialsException.class);

            // Family must be revoked
            verify(familyRepo).save(family);
            assertThat(family.isRevoked()).isTrue();
            assertThat(family.getRevokedReason()).isEqualTo("REFRESH_TOKEN_REUSE");

            // All tokens in family revoked
            verify(tokenRepo).saveAll(anyList());
            assertThat(consumedToken.isRevoked()).isTrue();

            // Critical SIEM event published
            verify(securityPublisher).publishReuseDetected(
                    eq(family), eq("1.2.3.4"), eq("MaliciousClient"), anyString());
        }

        @Test
        @DisplayName("revocation cascade marks every token in family revoked")
        void revocation_cascade_marks_all_tokens() {
            AppUser user = activeUser();
            Instant expiresAt = now.plusSeconds(3600);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken t1 = unconsumedToken(family, hash);
            t1.consume();
            RefreshToken t2 = unconsumedToken(family,
                    RefreshTokenService.sha256Hex("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"));
            RefreshToken t3 = unconsumedToken(family,
                    RefreshTokenService.sha256Hex("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"));

            when(tokenRepo.consumeToken(hash)).thenReturn(0);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.of(t1));
            when(tokenRepo.findByFamilyId(family.getId())).thenReturn(List.of(t1, t2, t3));

            assertThatThrownBy(() -> service.rotate(VALID_HANDLE, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            assertThat(t1.isRevoked()).isTrue();
            assertThat(t2.isRevoked()).isTrue();
            assertThat(t3.isRevoked()).isTrue();
        }
    }

    // ---- Already-revoked family (SIEM dedup) ----------------------------------

    @Nested
    @DisplayName("RevokedFamilyDedup")
    class RevokedFamilyDedup {

        @Test
        @DisplayName("0 rows affected with already-revoked family returns 401 without new event")
        void already_revoked_family_no_duplicate_event() {
            AppUser user = activeUser();
            Instant expiresAt = now.plusSeconds(3600);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            family.revoke("REFRESH_TOKEN_REUSE"); // already revoked
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken token = unconsumedToken(family, hash);
            token.consume();

            when(tokenRepo.consumeToken(hash)).thenReturn(0);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.rotate(VALID_HANDLE, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            // No new revocation or event — dedup
            verify(familyRepo, never()).save(any());
            verify(securityPublisher, never()).publishReuseDetected(any(), any(), any(), any());
        }
    }

    // ---- Expired family --------------------------------------------------------

    @Nested
    @DisplayName("ExpiredFamily")
    class ExpiredFamily {

        @Test
        @DisplayName("handle from expired family returns 401 without issuing new token")
        void expired_family_rejected() {
            AppUser user = activeUser();
            // Family expired 1 second ago
            Instant expiresAt = now.minusSeconds(1);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken token = unconsumedToken(family, hash);

            when(tokenRepo.consumeToken(hash)).thenReturn(1);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.rotate(VALID_HANDLE, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(tokenIssuer, never()).issue(any(), any());
            verify(tokenRepo, never()).save(any());
        }
    }

    // ---- Unknown hash ----------------------------------------------------------

    @Nested
    @DisplayName("UnknownHash")
    class UnknownHash {

        @Test
        @DisplayName("0 rows affected with hash not in DB returns 401 without any revocation")
        void unknown_hash_returns_401() {
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);

            when(tokenRepo.consumeToken(hash)).thenReturn(0);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rotate(VALID_HANDLE, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(familyRepo, never()).save(any());
            verify(securityPublisher, never()).publishReuseDetected(any(), any(), any(), any());
        }
    }

    // ---- Deactivated owner -----------------------------------------------------

    @Nested
    @DisplayName("DeactivatedOwner")
    class DeactivatedOwner {

        @Test
        @DisplayName("valid handle from deactivated user returns 401")
        void deactivated_user_handle_rejected() {
            AppUser user = activeUser();
            user.deactivate();
            Instant expiresAt = now.plusSeconds(3600);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken token = unconsumedToken(family, hash);

            when(tokenRepo.consumeToken(hash)).thenReturn(1);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.rotate(VALID_HANDLE, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(tokenIssuer, never()).issue(any(), any());
        }
    }

    // ---- Handle format validation ----------------------------------------------

    @Nested
    @DisplayName("HandleFormatValidation")
    class HandleFormatValidation {

        @Test
        @DisplayName("null handle throws before DB access")
        void null_handle_rejected() {
            assertThatThrownBy(() -> service.rotate(null, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(tokenRepo, never()).consumeToken(any());
        }

        @Test
        @DisplayName("handle with invalid base64url characters rejected before DB access")
        void invalid_chars_rejected() {
            String invalid = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".replace('A', '+');

            assertThatThrownBy(() -> service.rotate(invalid, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(tokenRepo, never()).consumeToken(any());
        }

        @Test
        @DisplayName("handle shorter than 43 chars rejected before DB access")
        void short_handle_rejected() {
            assertThatThrownBy(() -> service.rotate("AAAA", "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(tokenRepo, never()).consumeToken(any());
        }

        @Test
        @DisplayName("handle longer than 43 chars rejected before DB access")
        void long_handle_rejected() {
            String tooLong = "A".repeat(50);

            assertThatThrownBy(() -> service.rotate(tooLong, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(tokenRepo, never()).consumeToken(any());
        }
    }

    // ---- Racing revocation guard -----------------------------------------------

    @Nested
    @DisplayName("RacingRevocationGuard")
    class RacingRevocationGuard {

        @Test
        @DisplayName("1 row affected but family revoked by concurrent request returns 401")
        void racing_revocation_returns_401() {
            AppUser user = activeUser();
            Instant expiresAt = now.plusSeconds(3600);
            RefreshTokenFamily family = activeFamily(user, expiresAt);
            family.revoke("REFRESH_TOKEN_REUSE"); // revoked between UPDATE and load
            String hash = RefreshTokenService.sha256Hex(VALID_HANDLE);
            RefreshToken token = unconsumedToken(family, hash);

            when(tokenRepo.consumeToken(hash)).thenReturn(1);
            when(tokenRepo.findByTokenHash(hash)).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.rotate(VALID_HANDLE, "x", ""))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(tokenIssuer, never()).issue(any(), any());
        }
    }

    // ---- sha256Hex helper ------------------------------------------------------

    @Test
    @DisplayName("sha256Hex of same input produces same output")
    void sha256hex_deterministic() {
        String h1 = RefreshTokenService.sha256Hex(VALID_HANDLE);
        String h2 = RefreshTokenService.sha256Hex(VALID_HANDLE);
        assertThat(h1).isEqualTo(h2).hasSize(64);
        assertThat(h1).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("sha256Hex of different inputs produces different outputs")
    void sha256hex_different_inputs() {
        String h1 = RefreshTokenService.sha256Hex("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        String h2 = RefreshTokenService.sha256Hex("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertThat(h1).isNotEqualTo(h2);
    }
}
