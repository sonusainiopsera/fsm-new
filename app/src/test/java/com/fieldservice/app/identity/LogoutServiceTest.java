package com.fieldservice.app.identity;

import com.fieldservice.identity.application.LogoutService;
import com.fieldservice.identity.application.SecurityEventPublisher;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.token.JtiDenylist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LogoutService} covering residual-TTL boundaries, idempotency,
 * missing cookie, expired token, already-revoked family, and Redis-failure path.
 *
 * <p>Uses a pass-through {@link TransactionTemplate} so transactional logic runs
 * inline without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    @Mock RefreshTokenRepository       tokenRepository;
    @Mock RefreshTokenFamilyRepository familyRepository;
    @Mock SecurityEventPublisher       securityEventPublisher;
    @Mock JtiDenylist                  jtiDenylist;

    private LogoutService logoutService;
    private TransactionTemplate passthroughTx;

    @BeforeEach
    void setUp() {
        // Pass-through tx: executes callback immediately without a real transaction
        passthroughTx = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                try {
                    return action.doInTransaction(
                            new org.springframework.transaction.support.SimpleTransactionStatus(false));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        logoutService = new LogoutService(
                tokenRepository,
                familyRepository,
                securityEventPublisher,
                passthroughTx,
                List.of(jtiDenylist));
    }

    // ---- no-op paths -------------------------------------------------------

    @Test
    @DisplayName("null cookie returns NO_OP without touching the DB")
    void null_cookie_is_no_op() {
        var status = logoutService.logout(null, null, null);
        assertThat(status).isEqualTo(LogoutService.LogoutStatus.NO_OP);
        verify(tokenRepository, never()).findByTokenHash(any());
    }

    @Test
    @DisplayName("blank cookie returns NO_OP")
    void blank_cookie_is_no_op() {
        var status = logoutService.logout("   ", null, null);
        assertThat(status).isEqualTo(LogoutService.LogoutStatus.NO_OP);
        verify(tokenRepository, never()).findByTokenHash(any());
    }

    @Test
    @DisplayName("malformed handle (wrong length) returns NO_OP")
    void malformed_handle_returns_no_op() {
        var status = logoutService.logout("not-a-valid-handle", null, null);
        assertThat(status).isEqualTo(LogoutService.LogoutStatus.NO_OP);
        verify(tokenRepository, never()).findByTokenHash(any());
    }

    @Test
    @DisplayName("unknown handle (hash not in DB) returns NO_OP")
    void unknown_handle_returns_no_op() {
        String handle = "A".repeat(43);
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        var status = logoutService.logout(handle, null, null);
        assertThat(status).isEqualTo(LogoutService.LogoutStatus.NO_OP);
    }

    @Test
    @DisplayName("already-revoked family returns NO_OP idempotently")
    void already_revoked_family_is_no_op() {
        String handle = "B".repeat(43);
        RefreshTokenFamily family = buildRevokedFamily();
        RefreshToken token = buildToken(family);

        when(tokenRepository.findByTokenHash(sha256Hex(handle))).thenReturn(Optional.of(token));

        var status = logoutService.logout(handle, null, null);

        assertThat(status).isEqualTo(LogoutService.LogoutStatus.NO_OP);
        verify(familyRepository, never()).save(any());
    }

    // ---- success paths -----------------------------------------------------

    @Test
    @DisplayName("valid cookie revokes family and all tokens")
    void valid_cookie_revokes_family_and_tokens() {
        String handle = "C".repeat(43);
        RefreshTokenFamily family = buildActiveFamily();
        RefreshToken token1 = buildToken(family);
        RefreshToken token2 = buildToken(family);

        when(tokenRepository.findByTokenHash(sha256Hex(handle))).thenReturn(Optional.of(token1));
        when(tokenRepository.findByFamilyId(family.getId())).thenReturn(List.of(token1, token2));
        when(tokenRepository.saveAll(any())).thenReturn(List.of(token1, token2));
        when(familyRepository.save(family)).thenReturn(family);

        var status = logoutService.logout(handle, null, null);

        assertThat(status).isEqualTo(LogoutService.LogoutStatus.REVOKED);
        assertThat(token1.isRevoked()).isTrue();
        assertThat(token2.isRevoked()).isTrue();
        assertThat(family.isRevoked()).isTrue();
        verify(securityEventPublisher).publishLogout(eq(family), any(), any());
    }

    @Test
    @DisplayName("valid cookie + valid jti denylists the access token")
    void valid_cookie_and_jti_denylists_access_token() {
        String handle = "D".repeat(43);
        String jti = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plusSeconds(300);

        RefreshTokenFamily family = buildActiveFamily();
        RefreshToken token = buildToken(family);

        when(tokenRepository.findByTokenHash(sha256Hex(handle))).thenReturn(Optional.of(token));
        when(tokenRepository.findByFamilyId(family.getId())).thenReturn(List.of(token));
        when(tokenRepository.saveAll(any())).thenReturn(List.of(token));
        when(familyRepository.save(family)).thenReturn(family);

        var status = logoutService.logout(handle, jti, expiry);

        assertThat(status).isEqualTo(LogoutService.LogoutStatus.REVOKED);
        verify(jtiDenylist).deny(jti, expiry);
    }

    // ---- residual TTL boundary tests --------------------------------------

    @Test
    @DisplayName("already-expired access token: denylist skipped via JtiDenylist.deny (zero TTL)")
    void expired_access_token_skips_denylist_write() {
        String handle = "E".repeat(43);
        String jti = UUID.randomUUID().toString();
        Instant expiredAt = Instant.now().minusSeconds(60);

        RefreshTokenFamily family = buildActiveFamily();
        RefreshToken token = buildToken(family);

        when(tokenRepository.findByTokenHash(sha256Hex(handle))).thenReturn(Optional.of(token));
        when(tokenRepository.findByFamilyId(family.getId())).thenReturn(List.of(token));
        when(tokenRepository.saveAll(any())).thenReturn(List.of(token));
        when(familyRepository.save(family)).thenReturn(family);

        // JtiDenylist.deny() returns without storing when TTL <= 0
        var status = logoutService.logout(handle, jti, expiredAt);

        assertThat(status).isEqualTo(LogoutService.LogoutStatus.REVOKED);
        // deny() is still called; it internally skips storage for expired TTL
        verify(jtiDenylist).deny(jti, expiredAt);
    }

    @Test
    @DisplayName("no jti claim: family revoked, denylist not called")
    void no_jti_no_denylist_call() {
        String handle = "F".repeat(43);

        RefreshTokenFamily family = buildActiveFamily();
        RefreshToken token = buildToken(family);

        when(tokenRepository.findByTokenHash(sha256Hex(handle))).thenReturn(Optional.of(token));
        when(tokenRepository.findByFamilyId(family.getId())).thenReturn(List.of(token));
        when(tokenRepository.saveAll(any())).thenReturn(List.of(token));
        when(familyRepository.save(family)).thenReturn(family);

        var status = logoutService.logout(handle, null, null);

        assertThat(status).isEqualTo(LogoutService.LogoutStatus.REVOKED);
        verify(jtiDenylist, never()).deny(any(), any());
    }

    // ---- Redis-failure path ------------------------------------------------

    @Test
    @DisplayName("Redis unavailability returns REVOKED_DENYLIST_FAILED; family stays revoked")
    void redis_failure_returns_denylist_failed_not_rollback() {
        String handle = "G".repeat(43);
        String jti = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plusSeconds(300);

        RefreshTokenFamily family = buildActiveFamily();
        RefreshToken token = buildToken(family);

        when(tokenRepository.findByTokenHash(sha256Hex(handle))).thenReturn(Optional.of(token));
        when(tokenRepository.findByFamilyId(family.getId())).thenReturn(List.of(token));
        when(tokenRepository.saveAll(any())).thenReturn(List.of(token));
        when(familyRepository.save(family)).thenReturn(family);
        doThrow(new RuntimeException("Redis connection refused")).when(jtiDenylist).deny(any(), any());

        var status = logoutService.logout(handle, jti, expiry);

        assertThat(status).isEqualTo(LogoutService.LogoutStatus.REVOKED_DENYLIST_FAILED);
        // Family was revoked before the denylist failure
        assertThat(family.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("logout without denylist configured (null denylist) returns REVOKED")
    void null_denylist_revokes_without_error() {
        LogoutService noDenylistService = new LogoutService(
                tokenRepository, familyRepository, securityEventPublisher,
                passthroughTx, List.of() /* empty — no denylist */);

        String handle = "H".repeat(43);
        String jti = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plusSeconds(300);

        RefreshTokenFamily family = buildActiveFamily();
        RefreshToken token = buildToken(family);

        when(tokenRepository.findByTokenHash(sha256Hex(handle))).thenReturn(Optional.of(token));
        when(tokenRepository.findByFamilyId(family.getId())).thenReturn(List.of(token));
        when(tokenRepository.saveAll(any())).thenReturn(List.of(token));
        when(familyRepository.save(family)).thenReturn(family);

        var status = noDenylistService.logout(handle, jti, expiry);
        assertThat(status).isEqualTo(LogoutService.LogoutStatus.REVOKED);
    }

    // ---- helpers -----------------------------------------------------------

    private RefreshTokenFamily buildActiveFamily() {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(UUID.randomUUID());

        RefreshTokenFamily family = RefreshTokenFamily.open(user, Instant.now().plusSeconds(86400));
        return family;
    }

    private RefreshTokenFamily buildRevokedFamily() {
        RefreshTokenFamily family = buildActiveFamily();
        family.revoke("USER_LOGOUT");
        return family;
    }

    private RefreshToken buildToken(RefreshTokenFamily family) {
        return RefreshToken.issue(family, "fakehash64" + "x".repeat(54), Instant.now().plusSeconds(86400));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
