package com.fieldservice.identity;

import com.fieldservice.identity.application.StreamTicketService;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.identity.token.StreamTicketStore;
import com.fieldservice.platform.api.exception.AuthDependencyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamTicketService}.
 *
 * <p>Uses an in-memory fake {@link StreamTicketStore} and a controllable {@link Clock}
 * to verify TTL boundaries, binding checks, single-use consumption and fail-closed paths
 * without requiring a running Redis instance.
 */
class StreamTicketServiceUnitTest {

    private static final String CLIENT_IP  = "10.0.0.1";
    private static final String OTHER_IP   = "10.0.0.99";
    private static final UUID   USER_ID    = UUID.randomUUID();
    private static final String USER_ID_STR = USER_ID.toString();

    private InMemoryTicketStore  store;
    private AppUserRepository    userRepo;
    private SimpleMeterRegistry  meterRegistry;
    private Clock                fixedClock;
    private StreamTicketService  service;

    @BeforeEach
    void setUp() {
        store         = new InMemoryTicketStore();
        userRepo      = mock(AppUserRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        fixedClock    = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        AppUser activeUser = mock(AppUser.class);
        when(activeUser.getActive()).thenReturn(Boolean.TRUE);
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(activeUser));

        service = new StreamTicketService(
                List.of(store), List.of(), userRepo, meterRegistry, fixedClock);
    }

    // -----------------------------------------------------------------------
    // Ticket generation entropy and encoding
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Ticket value is 43-char base64url (256-bit entropy)")
    void ticket_is_43_char_base64url() {
        Authentication auth = dispatcher();
        StreamTicketService.StreamTicketResult r = service.issue(auth, CLIENT_IP);

        // 32 random bytes → 43 base64url chars without padding
        assertThat(r.ticketValue()).hasSize(43);
        assertThat(r.ticketValue()).matches("[A-Za-z0-9_-]+");
        // Verify it decodes to 32 bytes
        byte[] decoded = Base64.getUrlDecoder().decode(r.ticketValue() + "="); // pad for decode
        assertThat(decoded).hasSize(32);
    }

    @Test
    @DisplayName("Two issued tickets have different values (entropy check)")
    void consecutive_tickets_differ() {
        StreamTicketService.StreamTicketResult a = service.issue(dispatcher(), CLIENT_IP);
        StreamTicketService.StreamTicketResult b = service.issue(dispatcher(), CLIENT_IP);
        assertThat(a.ticketValue()).isNotEqualTo(b.ticketValue());
    }

    // -----------------------------------------------------------------------
    // TTL boundary — exactly 60 seconds
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("expiresIn is exactly 60 seconds")
    void expires_in_is_60() {
        StreamTicketService.StreamTicketResult r = service.issue(dispatcher(), CLIENT_IP);
        assertThat(r.expiresIn()).isEqualTo(60);
        assertThat(r.expiresAt())
                .isEqualTo(fixedClock.instant().plus(Duration.ofSeconds(60)));
    }

    // -----------------------------------------------------------------------
    // Single-use consumption
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Ticket can be redeemed exactly once; second redemption throws")
    void single_use_enforced() {
        String ticket = service.issue(dispatcher(), CLIENT_IP).ticketValue();

        Authentication first = service.redeem(ticket, CLIENT_IP);
        assertThat(first).isNotNull();

        assertThatThrownBy(() -> service.redeem(ticket, CLIENT_IP))
                .isInstanceOf(StreamTicketService.StreamTicketRedeemException.class);
    }

    // -----------------------------------------------------------------------
    // User binding
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Resolved authentication carries originating authorities")
    void redeemed_auth_carries_originating_authorities() {
        Authentication auth = dispatcher();
        String ticket = service.issue(auth, CLIENT_IP).ticketValue();

        Authentication redeemed = service.redeem(ticket, CLIENT_IP);
        assertThat(redeemed.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_DISPATCHER");
    }

    // -----------------------------------------------------------------------
    // IP binding
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Redemption from a different IP is rejected")
    void ip_mismatch_rejected() {
        String ticket = service.issue(dispatcher(), CLIENT_IP).ticketValue();

        assertThatThrownBy(() -> service.redeem(ticket, OTHER_IP))
                .isInstanceOf(StreamTicketService.StreamTicketRedeemException.class);
    }

    // -----------------------------------------------------------------------
    // JTI denylist
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Ticket with denylisted JTI is rejected at redemption")
    void denylisted_jti_rejected() {
        String jtiToRevoke = "test-jti-to-revoke";
        JtiDenylist denylist = mock(JtiDenylist.class);
        when(denylist.isDenied(jtiToRevoke)).thenReturn(true);

        StreamTicketService svcWithDenylist = new StreamTicketService(
                List.of(store), List.of(denylist), userRepo, meterRegistry, fixedClock);

        // Issue a ticket with this JTI (simulated via store directly)
        String ticket = svcWithDenylist.issue(dispatcher(), CLIENT_IP).ticketValue();

        // Override stored payload to embed the revoked JTI
        store.forceJti(ticket, jtiToRevoke);

        assertThatThrownBy(() -> svcWithDenylist.redeem(ticket, CLIENT_IP))
                .isInstanceOf(StreamTicketService.StreamTicketRedeemException.class);
    }

    // -----------------------------------------------------------------------
    // Account inactive
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Ticket for a deactivated account is rejected at redemption")
    void inactive_account_rejected() {
        AppUser inactiveUser = mock(AppUser.class);
        when(inactiveUser.getActive()).thenReturn(Boolean.FALSE);
        UUID inactiveId = UUID.randomUUID();
        when(userRepo.findById(inactiveId)).thenReturn(Optional.of(inactiveUser));

        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                inactiveId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN")));

        String ticket = service.issue(auth, CLIENT_IP).ticketValue();

        assertThatThrownBy(() -> service.redeem(ticket, CLIENT_IP))
                .isInstanceOf(StreamTicketService.StreamTicketRedeemException.class);
    }

    // -----------------------------------------------------------------------
    // Fail-closed: Redis unavailable at issuance
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Issuance with no store (Redis absent) throws AuthDependencyUnavailableException")
    void issue_fail_closed_when_no_store() {
        StreamTicketService noRedisService = new StreamTicketService(
                List.of(), List.of(), userRepo, meterRegistry, fixedClock);

        assertThatThrownBy(() -> noRedisService.issue(dispatcher(), CLIENT_IP))
                .isInstanceOf(AuthDependencyUnavailableException.class);
    }

    @Test
    @DisplayName("Redemption with no store (Redis absent) throws StreamTicketRedeemException")
    void redeem_fail_closed_when_no_store() {
        StreamTicketService noRedisService = new StreamTicketService(
                List.of(), List.of(), userRepo, meterRegistry, fixedClock);

        assertThatThrownBy(() -> noRedisService.redeem("any-ticket", CLIENT_IP))
                .isInstanceOf(StreamTicketService.StreamTicketRedeemException.class);
    }

    @Test
    @DisplayName("Store error during issuance throws AuthDependencyUnavailableException")
    void issue_wraps_store_exception() {
        StreamTicketStore failingStore = mock(StreamTicketStore.class);
        // store() throws a runtime exception (simulating Redis connectivity failure)
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(failingStore).store(any(), any());

        StreamTicketService svc = new StreamTicketService(
                List.of(failingStore), List.of(), userRepo, meterRegistry, fixedClock);

        assertThatThrownBy(() -> svc.issue(dispatcher(), CLIENT_IP))
                .isInstanceOf(AuthDependencyUnavailableException.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Authentication dispatcher() {
        return UsernamePasswordAuthenticationToken.authenticated(
                USER_ID_STR, null,
                List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER")));
    }

    /**
     * In-memory fake store — no Redis required.
     * Delegates key computation to the real {@link StreamTicketStore#keyFor} static helper.
     */
    static class InMemoryTicketStore extends StreamTicketStore {

        private final Map<String, StreamTicketStore.TicketPayload> data = new HashMap<>();

        InMemoryTicketStore() {
            super(null); // null redis — we override all methods
        }

        @Override
        public void store(String ticketValue, StreamTicketStore.TicketPayload payload) {
            data.put(keyFor(ticketValue), payload);
        }

        @Override
        public Optional<StreamTicketStore.TicketPayload> redeem(String ticketValue) {
            String key = keyFor(ticketValue);
            StreamTicketStore.TicketPayload payload = data.remove(key);
            return Optional.ofNullable(payload);
        }

        /** Test helper to force a specific JTI into a stored payload (without re-issuing). */
        void forceJti(String ticketValue, String newJti) {
            String key = keyFor(ticketValue);
            StreamTicketStore.TicketPayload existing = data.get(key);
            if (existing == null) throw new IllegalStateException("No stored ticket for key");
            data.put(key, new StreamTicketStore.TicketPayload(
                    existing.userId(), existing.authorities(),
                    existing.clientIp(), existing.issuedAt(), newJti));
        }
    }
}
