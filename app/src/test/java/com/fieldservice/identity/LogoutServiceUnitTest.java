package com.fieldservice.identity;

import com.fieldservice.identity.application.LogoutService;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.token.InMemoryJtiDenylist;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.platform.api.DomainEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LogoutService} covering TTL boundary conditions,
 * idempotency branches and Redis failure path — no Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LogoutService unit tests")
class LogoutServiceUnitTest {

    @Mock
    RefreshTokenRepository tokenRepository;
    @Mock
    RefreshTokenFamilyRepository familyRepository;
    @Mock
    DomainEventPublisher eventPublisher;
    @Mock
    TransactionTemplate txTemplate;

    // Real in-memory denylist so we can observe revoke() side effects.
    InMemoryJtiDenylist denylist;
    SimpleMeterRegistry meterRegistry;
    LogoutService service;

    @BeforeEach
    void setUp() {
        denylist = new InMemoryJtiDenylist();
        meterRegistry = new SimpleMeterRegistry();

        // Make TransactionTemplate execute the callback synchronously (no real tx).
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        service = new LogoutService(tokenRepository, familyRepository, denylist,
                eventPublisher, meterRegistry, txTemplate);
    }

    // -----------------------------------------------------------------------
    // computeResidualTtl boundary tests (AC-10: TTL boundaries)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("computeResidualTtl: already expired → Duration.ZERO")
    void residualTtl_alreadyExpired_returnsZero() {
        Instant expired = Instant.now().minusSeconds(10);
        Duration ttl = LogoutService.computeResidualTtl(expired);
        assertThat(ttl).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("computeResidualTtl: one second remaining → ~1 s")
    void residualTtl_oneSecondRemaining_returnsOneSecond() {
        Instant almostExpired = Instant.now().plusSeconds(1);
        Duration ttl = LogoutService.computeResidualTtl(almostExpired);
        assertThat(ttl.toSeconds()).isEqualTo(1L);
    }

    @Test
    @DisplayName("computeResidualTtl: full 15-minute lifetime → ~900 s")
    void residualTtl_fullLifetime_returns900Seconds() {
        Instant freshToken = Instant.now().plusSeconds(900);
        Duration ttl = LogoutService.computeResidualTtl(freshToken);
        // Allow ±2 s for test execution time
        assertThat(ttl.toSeconds()).isBetween(895L, 900L);
    }

    // -----------------------------------------------------------------------
    // Idempotency / no-op branches (AC-10: idempotency branches)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("No cookie → 204 Success, no family lookup")
    void noCookie_returnsSuccess_noFamilyLookup() {
        LogoutService.LogoutResult result = service.logout(null, null, null, "trace-1");
        assertThat(result).isInstanceOf(LogoutService.LogoutResult.Success.class);
        verify(tokenRepository, never()).findByTokenHash(any());
    }

    @Test
    @DisplayName("Unknown handle → 204 Success (idempotent)")
    void unknownHandle_returnsSuccess() {
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        LogoutService.LogoutResult result = service.logout("anyHandle", null, null, "trace-2");
        assertThat(result).isInstanceOf(LogoutService.LogoutResult.Success.class);
    }

    @Test
    @DisplayName("Already-revoked family → 204 Success (idempotent)")
    void alreadyRevokedFamily_returnsSuccess() {
        UUID familyId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();

        RefreshToken token = mockToken(familyId);
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        RefreshTokenFamily family = mockFamily(familyId, userId, true);
        when(familyRepository.findById(familyId)).thenReturn(Optional.of(family));

        LogoutService.LogoutResult result = service.logout(validHandle(), null, null, "trace-3");
        assertThat(result).isInstanceOf(LogoutService.LogoutResult.Success.class);
        verify(familyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Missing family record → 204 Success (idempotent)")
    void missingFamily_returnsSuccess() {
        UUID familyId = UUID.randomUUID();
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(mockToken(familyId)));
        when(familyRepository.findById(familyId)).thenReturn(Optional.empty());

        LogoutService.LogoutResult result = service.logout(validHandle(), null, null, "trace-4");
        assertThat(result).isInstanceOf(LogoutService.LogoutResult.Success.class);
    }

    // -----------------------------------------------------------------------
    // Expired access token (AC-10: expired token → skip denylist)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Expired access token → family revoked, denylist skipped, 204 Success")
    void expiredToken_familyRevoked_denylistSkipped() {
        UUID familyId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();

        RefreshToken token = mockToken(familyId);
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        RefreshTokenFamily family = mockFamily(familyId, userId, false);
        when(familyRepository.findById(familyId)).thenReturn(Optional.of(family));

        Instant expiredAt = Instant.now().minusSeconds(60);

        LogoutService.LogoutResult result = service.logout(validHandle(), jti, expiredAt, "trace-5");

        assertThat(result).isInstanceOf(LogoutService.LogoutResult.Success.class);
        // Family must be revoked
        verify(familyRepository).save(family);
        // jti must NOT be in denylist (residualTtl = 0)
        assertThat(denylist.isRevoked(jti)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Absent token (AC-10: missing cookie / no access token cases)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("No access token → family revoked but denylist not touched, 204 Success")
    void noAccessToken_familyRevoked_noDenylistEntry() {
        UUID familyId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();

        RefreshToken token = mockToken(familyId);
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        RefreshTokenFamily family = mockFamily(familyId, userId, false);
        when(familyRepository.findById(familyId)).thenReturn(Optional.of(family));

        LogoutService.LogoutResult result = service.logout(validHandle(), null, null, "trace-6");

        assertThat(result).isInstanceOf(LogoutService.LogoutResult.Success.class);
        verify(familyRepository).save(family);
    }

    // -----------------------------------------------------------------------
    // Redis failure path (AC-10)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Redis denylist failure → DenylistFailure (family still revoked)")
    void redisDenylistFailure_returnsDenylistFailure_familyRevoked() {
        UUID familyId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();

        RefreshToken token = mockToken(familyId);
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        RefreshTokenFamily family = mockFamily(familyId, userId, false);
        when(familyRepository.findById(familyId)).thenReturn(Optional.of(family));

        // Use a failing denylist
        JtiDenylist failingDenylist = new FailingJtiDenylist();
        LogoutService serviceWithFailingDenylist = new LogoutService(
                tokenRepository, familyRepository, failingDenylist,
                eventPublisher, meterRegistry, txTemplate);

        Instant validExp = Instant.now().plusSeconds(60);
        LogoutService.LogoutResult result =
                serviceWithFailingDenylist.logout(validHandle(), jti, validExp, "trace-7");

        assertThat(result).isInstanceOf(LogoutService.LogoutResult.DenylistFailure.class);
        // Family MUST still be revoked even though denylist failed
        verify(familyRepository).save(family);
        // Metric incremented
        assertThat(meterRegistry.counter("auth.logout.denylist_insert_failure").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Successful logout → jti is in denylist")
    void successfulLogout_jtiDenylisted() {
        UUID familyId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();

        RefreshToken token = mockToken(familyId);
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        RefreshTokenFamily family = mockFamily(familyId, userId, false);
        when(familyRepository.findById(familyId)).thenReturn(Optional.of(family));

        Instant validExp = Instant.now().plusSeconds(900);
        LogoutService.LogoutResult result = service.logout(validHandle(), jti, validExp, "trace-8");

        assertThat(result).isInstanceOf(LogoutService.LogoutResult.Success.class);
        assertThat(denylist.isRevoked(jti)).isTrue();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String validHandle() {
        // 43-char base64url value matching the expected handle format
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    }

    private static RefreshToken mockToken(UUID familyId) {
        RefreshToken t = mock(RefreshToken.class);
        when(t.getFamilyId()).thenReturn(familyId);
        return t;
    }

    private static RefreshTokenFamily mockFamily(UUID id, UUID userId, boolean revoked) {
        RefreshTokenFamily f = mock(RefreshTokenFamily.class);
        when(f.getId()).thenReturn(id);
        when(f.getUserId()).thenReturn(userId);
        when(f.isRevoked()).thenReturn(revoked);
        return f;
    }

    /** JtiDenylist that always throws DenylistUnavailableException on revoke(). */
    private static class FailingJtiDenylist implements JtiDenylist {
        @Override
        public boolean isRevoked(String jti) { return false; }

        @Override
        public void revoke(String jti, Duration ttl) {
            throw new DenylistUnavailableException("Redis unavailable (test)", new RuntimeException());
        }
    }
}
