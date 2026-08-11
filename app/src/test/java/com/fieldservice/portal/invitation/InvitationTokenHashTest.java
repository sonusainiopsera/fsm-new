package com.fieldservice.portal.invitation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for invitation token generation and SHA-256 hashing (WO-169, AC-8, AC-9).
 *
 * <p>No Spring context required — exercises static helpers on InvitationService only.
 */
@DisplayName("InvitationService token hash unit tests")
class InvitationTokenHashTest {

    @Test
    @DisplayName("Raw token is 43 characters of URL-safe base64 (256 bits, no padding)")
    void rawToken_hasCorrectLength() {
        String token = InvitationService.generateRawToken();
        // 32 bytes base64url without padding = ceil(32*8/6) = 43 chars
        assertThat(token).hasSize(43);
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @RepeatedTest(5)
    @DisplayName("Each raw token is unique (256-bit randomness collision probability is negligible)")
    void rawToken_isUnique() {
        String a = InvitationService.generateRawToken();
        String b = InvitationService.generateRawToken();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Token hash is a 64-character lowercase hex SHA-256 digest")
    void tokenHash_isHexSha256() {
        String token = InvitationService.generateRawToken();
        String hash = InvitationService.hashToken(token);
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Same raw token always produces the same hash (deterministic)")
    void tokenHash_isDeterministic() {
        String token = "some-fixed-test-token";
        String hash1 = InvitationService.hashToken(token);
        String hash2 = InvitationService.hashToken(token);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("Different raw tokens produce different hashes (preimage resistance)")
    void tokenHash_differentForDifferentTokens() {
        String h1 = InvitationService.hashToken(InvitationService.generateRawToken());
        String h2 = InvitationService.hashToken(InvitationService.generateRawToken());
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("Raw token is not stored: hash != raw token")
    void rawToken_notEqualToHash() {
        String raw = InvitationService.generateRawToken();
        String hash = InvitationService.hashToken(raw);
        assertThat(raw).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("Expiry guard: isExpired() returns false before expiry")
    void invitation_notExpiredBeforeExpiresAt() {
        java.time.Instant future = java.time.Instant.now().plusSeconds(3600);
        var inv = new com.fieldservice.portal.domain.PortalInvitation(
                java.util.UUID.randomUUID(), "test@example.com", "Test", "hash", future);
        assertThat(inv.isExpired(java.time.Instant.now())).isFalse();
    }

    @Test
    @DisplayName("Expiry guard: isExpired() returns true after expiry")
    void invitation_expiredAfterExpiresAt() {
        java.time.Instant past = java.time.Instant.now().minusSeconds(1);
        var inv = new com.fieldservice.portal.domain.PortalInvitation(
                java.util.UUID.randomUUID(), "test@example.com", "Test", "hash", past);
        assertThat(inv.isExpired(java.time.Instant.now())).isTrue();
    }

    @Test
    @DisplayName("Single-use guard: isConsumed() returns false before consumption")
    void invitation_notConsumedInitially() {
        var inv = new com.fieldservice.portal.domain.PortalInvitation(
                java.util.UUID.randomUUID(), "test@example.com", "Test", "hash",
                java.time.Instant.now().plusSeconds(3600));
        assertThat(inv.isConsumed()).isFalse();
    }

    @Test
    @DisplayName("Single-use guard: isConsumed() returns true after consume()")
    void invitation_consumedAfterConsume() {
        var inv = new com.fieldservice.portal.domain.PortalInvitation(
                java.util.UUID.randomUUID(), "test@example.com", "Test", "hash",
                java.time.Instant.now().plusSeconds(3600));
        inv.consume(java.time.Instant.now());
        assertThat(inv.isConsumed()).isTrue();
    }
}
