package com.fieldservice.portal.service;

import com.fieldservice.portal.domain.PortalAccountStatus;
import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.domain.PortalInvitation;
import com.fieldservice.portal.repository.PortalAccountUserRepository;
import com.fieldservice.portal.repository.PortalInvitationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PortalInvitationService} — no Spring context required.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Token generation is 256-bit / base64url (43 chars without padding).</li>
 *   <li>SHA-256 token hash round-trip.</li>
 *   <li>Expiry is 72 hours from {@code clock.instant()}.</li>
 *   <li>Activation creates the {@link PortalAccountUser} and marks the invitation consumed.</li>
 *   <li>Replay of consumed token throws InvitationNotFoundException.</li>
 *   <li>Expired token throws InvitationNotFoundException.</li>
 *   <li>Unknown token throws InvitationNotFoundException.</li>
 *   <li>Duplicate linkage throws DuplicateLinkageException.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PortalInvitationServiceTest {

    static final Instant NOW       = Instant.parse("2025-06-15T10:00:00Z");
    static final UUID ACCOUNT_ID   = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER_ID      = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID ISSUER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000003");

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock PortalInvitationRepository  invitationRepository;
    @Mock PortalAccountUserRepository accountUserRepository;

    PortalInvitationService service;

    @BeforeEach
    void setUp() {
        service = new PortalInvitationService(invitationRepository, accountUserRepository, clock);
    }

    // ---------------------------------------------------------------
    // Token utilities
    // ---------------------------------------------------------------

    @Test
    @DisplayName("generateToken: produces 43-char base64url string (256 bits, no padding)")
    void generateToken_is43Chars() {
        String token = PortalInvitationService.generateToken();
        assertThat(token).hasSize(43)
                .matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("sha256Hex: round-trip — hash of token matches re-hashing the same input")
    void sha256Hex_roundTrip() {
        String token = "test-token-value";
        String hash1 = PortalInvitationService.sha256Hex(token);
        String hash2 = PortalInvitationService.sha256Hex(token);
        assertThat(hash1).isEqualTo(hash2)
                .hasSize(64)
                .matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("sha256Hex: different inputs produce different hashes")
    void sha256Hex_differentInputs_differentHashes() {
        assertThat(PortalInvitationService.sha256Hex("aaa"))
                .isNotEqualTo(PortalInvitationService.sha256Hex("bbb"));
    }

    // ---------------------------------------------------------------
    // Issuance
    // ---------------------------------------------------------------

    @Test
    @DisplayName("issueInvitation: stores invitation and returns non-null plain token")
    void issueInvitation_storesInvitationAndReturnsToken() {
        when(invitationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.issueInvitation(ACCOUNT_ID, "enc@test.com", "Test User", ISSUER_ID);

        assertThat(result.plainToken()).isNotNull().hasSize(43);
        assertThat(result.invitationId()).isNotNull();
        assertThat(result.expiresAt())
                .isEqualTo(NOW.plusSeconds(72L * 3600));

        ArgumentCaptor<PortalInvitation> captor = ArgumentCaptor.forClass(PortalInvitation.class);
        verify(invitationRepository).save(captor.capture());
        PortalInvitation saved = captor.getValue();
        assertThat(saved.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(saved.getTokenHash())
                .isEqualTo(PortalInvitationService.sha256Hex(result.plainToken()));
        assertThat(saved.getCreatedBy()).isEqualTo(ISSUER_ID);
    }

    @Test
    @DisplayName("issueInvitation: expiry is exactly 72 hours from clock.instant()")
    void issueInvitation_expiryIs72Hours() {
        when(invitationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.issueInvitation(ACCOUNT_ID, null, null, ISSUER_ID);

        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(72 * 3600L));
    }

    // ---------------------------------------------------------------
    // Activation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("activateInvitation: creates PortalAccountUser and marks invitation consumed")
    void activateInvitation_createsLinkageAndConsumesToken() {
        String token = "valid-token-value";
        String hash  = PortalInvitationService.sha256Hex(token);
        PortalInvitation inv = new PortalInvitation(
                ACCOUNT_ID, "e@t.com", "Name", hash, NOW.plusSeconds(3600), ISSUER_ID);
        when(invitationRepository.findByTokenHash(hash)).thenReturn(Optional.of(inv));
        when(accountUserRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(invitationRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        when(accountUserRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        var result = service.activateInvitation(token, USER_ID);

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(inv.getConsumedAt()).isEqualTo(NOW);

        ArgumentCaptor<PortalAccountUser> pauCaptor = ArgumentCaptor.forClass(PortalAccountUser.class);
        verify(accountUserRepository).save(pauCaptor.capture());
        assertThat(pauCaptor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(pauCaptor.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(pauCaptor.getValue().getStatus()).isEqualTo(PortalAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("activateInvitation: throws InvitationNotFoundException for unknown token")
    void activateInvitation_throws_forUnknownToken() {
        String token = "unknown-token";
        when(invitationRepository.findByTokenHash(PortalInvitationService.sha256Hex(token)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateInvitation(token, USER_ID))
                .isInstanceOf(PortalInvitationService.InvitationNotFoundException.class);
        verify(accountUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("activateInvitation: throws InvitationNotFoundException for expired token")
    void activateInvitation_throws_forExpiredToken() {
        String token = "expired-token";
        String hash  = PortalInvitationService.sha256Hex(token);
        // Expired 1 second before NOW
        PortalInvitation inv = new PortalInvitation(
                ACCOUNT_ID, null, null, hash, NOW.minusSeconds(1), ISSUER_ID);
        when(invitationRepository.findByTokenHash(hash)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.activateInvitation(token, USER_ID))
                .isInstanceOf(PortalInvitationService.InvitationNotFoundException.class);
    }

    @Test
    @DisplayName("activateInvitation: throws InvitationNotFoundException for consumed token")
    void activateInvitation_throws_forConsumedToken() {
        String token = "consumed-token";
        String hash  = PortalInvitationService.sha256Hex(token);
        PortalInvitation inv = new PortalInvitation(
                ACCOUNT_ID, null, null, hash, NOW.plusSeconds(3600), ISSUER_ID);
        inv.consume(NOW.minusSeconds(60)); // already consumed
        when(invitationRepository.findByTokenHash(hash)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.activateInvitation(token, USER_ID))
                .isInstanceOf(PortalInvitationService.InvitationNotFoundException.class);
    }

    @Test
    @DisplayName("activateInvitation: throws DuplicateLinkageException when user already linked")
    void activateInvitation_throws_whenAlreadyLinked() {
        String token = "dup-token";
        String hash  = PortalInvitationService.sha256Hex(token);
        PortalInvitation inv = new PortalInvitation(
                ACCOUNT_ID, null, null, hash, NOW.plusSeconds(3600), ISSUER_ID);
        when(invitationRepository.findByTokenHash(hash)).thenReturn(Optional.of(inv));
        // User already has a linkage
        when(accountUserRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(new PortalAccountUser(USER_ID, ACCOUNT_ID)));

        assertThatThrownBy(() -> service.activateInvitation(token, USER_ID))
                .isInstanceOf(PortalInvitationService.DuplicateLinkageException.class);
    }
}
