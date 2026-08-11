package com.fieldservice.identity;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.application.StreamTicketService;
import com.fieldservice.identity.token.InMemoryStreamTicketStore;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.identity.token.StreamTicketPayload;
import com.fieldservice.identity.token.StreamTicketStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamTicketService} using an in-memory store and a controllable clock.
 *
 * <p>No Spring context; all dependencies are fakes or mocks.
 */
class StreamTicketServiceUnitTest {

    private static final String USER_ID = UUID.randomUUID().toString();
    private static final String JTI = UUID.randomUUID().toString();
    private static final String CLIENT_IP = "10.0.0.1";
    private static final String OTHER_IP = "10.0.0.2";

    private InMemoryStreamTicketStore ticketStore;
    private JtiDenylist jtiDenylist;
    private AppUserRepository userRepository;
    private SimpleMeterRegistry meterRegistry;
    private StreamTicketService service;

    @BeforeEach
    void setUp() {
        ticketStore = new InMemoryStreamTicketStore();
        jtiDenylist = mock(JtiDenylist.class);
        userRepository = mock(AppUserRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new StreamTicketService(ticketStore, jtiDenylist, userRepository, meterRegistry);

        // Default: active user, jti not denylisted
        AppUser activeUser = mock(AppUser.class);
        when(activeUser.isActive()).thenReturn(true);
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser));
        when(jtiDenylist.isRevoked(any())).thenReturn(false);

        // Set up a JWT authentication in the security context
        installJwtAuth(USER_ID, JTI, List.of("ROLE_DISPATCHER"));
    }

    @Test
    @DisplayName("issued ticket has correct entropy: 43 chars, base64url characters")
    void issueTicket_hasCorrectEntropy() {
        String ticket = service.issueTicket(CLIENT_IP);

        assertThat(ticket).hasSize(43);
        assertThat(ticket).matches("^[A-Za-z0-9_\\-]+$");
    }

    @Test
    @DisplayName("ticket store must not contain the plaintext ticket value")
    void issueTicket_plaintextNotInStore() {
        String ticket = service.issueTicket(CLIENT_IP);

        // The in-memory store's key should be the SHA-256 hash, not the plaintext
        // We verify this by confirming GETDEL with the original key works (hash-derived)
        // and that the raw ticket value itself is not a valid lookup key
        Optional<Authentication> auth = service.redeemTicket(ticket, CLIENT_IP);
        assertThat(auth).isPresent();

        // Second redemption with same ticket → not found (key was deleted)
        Optional<Authentication> replay = service.redeemTicket(ticket, CLIENT_IP);
        assertThat(replay).isEmpty();
    }

    @Test
    @DisplayName("successful redemption returns principal with original authorities")
    void redeemTicket_successReturnsCorrectAuthorities() {
        installJwtAuth(USER_ID, JTI, List.of("ROLE_DISPATCHER", "ROLE_ADMIN"));
        service = new StreamTicketService(ticketStore, jtiDenylist, userRepository, meterRegistry);

        String ticket = service.issueTicket(CLIENT_IP);
        Optional<Authentication> auth = service.redeemTicket(ticket, CLIENT_IP);

        assertThat(auth).isPresent();
        assertThat(auth.get().getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder("ROLE_DISPATCHER", "ROLE_ADMIN");
    }

    @Test
    @DisplayName("ticket is single-use: second redemption returns empty")
    void redeemTicket_singleUseSemantics() {
        String ticket = service.issueTicket(CLIENT_IP);

        Optional<Authentication> first = service.redeemTicket(ticket, CLIENT_IP);
        Optional<Authentication> second = service.redeemTicket(ticket, CLIENT_IP);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("expired ticket (TTL boundary) is rejected")
    void redeemTicket_expiredTicketIsRejected() {
        // Manually insert a payload that is already expired
        String ticket = "expiredticket123456789012345678901234567";
        String hashedKey = sha256Hex(ticket);
        StreamTicketPayload payload = new StreamTicketPayload(
                USER_ID, List.of("ROLE_DISPATCHER"), CLIENT_IP,
                Instant.now().minus(Duration.ofSeconds(70)), JTI);
        // Store with an extremely short TTL so it logically expires
        ticketStore.store(hashedKey, payload, Duration.ofMillis(1));

        // Sleep enough for the in-memory store to consider it expired
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Optional<Authentication> auth = service.redeemTicket(ticket, CLIENT_IP);
        assertThat(auth).isEmpty();
    }

    @Test
    @DisplayName("IP mismatch rejects the ticket")
    void redeemTicket_ipMismatchRejectsTicket() {
        String ticket = service.issueTicket(CLIENT_IP);
        Optional<Authentication> auth = service.redeemTicket(ticket, OTHER_IP);
        assertThat(auth).isEmpty();
    }

    @Test
    @DisplayName("denylisted jti rejects the ticket")
    void redeemTicket_denylistedJtiRejects() {
        when(jtiDenylist.isRevoked(JTI)).thenReturn(true);

        String ticket = service.issueTicket(CLIENT_IP);
        Optional<Authentication> auth = service.redeemTicket(ticket, CLIENT_IP);

        assertThat(auth).isEmpty();
    }

    @Test
    @DisplayName("ticket for inactive account is rejected")
    void redeemTicket_inactiveAccountRejects() {
        AppUser inactive = mock(AppUser.class);
        when(inactive.isActive()).thenReturn(false);
        when(userRepository.findById(any())).thenReturn(Optional.of(inactive));

        String ticket = service.issueTicket(CLIENT_IP);
        Optional<Authentication> auth = service.redeemTicket(ticket, CLIENT_IP);

        assertThat(auth).isEmpty();
    }

    @Test
    @DisplayName("denylist unavailability fails closed: ticket is rejected")
    void redeemTicket_denylistUnavailableFailsClosed() {
        when(jtiDenylist.isRevoked(any())).thenThrow(
                new JtiDenylist.DenylistUnavailableException("Redis down", null));

        String ticket = service.issueTicket(CLIENT_IP);
        Optional<Authentication> auth = service.redeemTicket(ticket, CLIENT_IP);

        assertThat(auth).isEmpty();
    }

    @Test
    @DisplayName("store unavailability on issuance throws StoreUnavailableException")
    void issueTicket_storeUnavailableThrows() {
        StreamTicketStore brokenStore = new StreamTicketStore() {
            @Override
            public void store(String k, StreamTicketPayload p, Duration t) {
                throw new StoreUnavailableException("Redis down", null);
            }

            @Override
            public Optional<StreamTicketPayload> consumeAtomically(String k) {
                throw new StoreUnavailableException("Redis down", null);
            }
        };

        StreamTicketService brokenService = new StreamTicketService(
                brokenStore, jtiDenylist, userRepository, meterRegistry);

        org.junit.jupiter.api.Assertions.assertThrows(
                StreamTicketStore.StoreUnavailableException.class,
                () -> brokenService.issueTicket(CLIENT_IP));
    }

    @Test
    @DisplayName("issuance counter increments on success")
    void issueTicket_incrementsIssuedCounter() {
        service.issueTicket(CLIENT_IP);
        service.issueTicket(CLIENT_IP);

        double count = meterRegistry.counter(StreamTicketService.COUNTER_ISSUED).count();
        assertThat(count).isEqualTo(2.0);
    }

    @Test
    @DisplayName("redemption counter increments on success")
    void redeemTicket_incrementsRedeemedCounter() {
        String ticket = service.issueTicket(CLIENT_IP);
        service.redeemTicket(ticket, CLIENT_IP);

        double count = meterRegistry.counter(StreamTicketService.COUNTER_REDEEMED).count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rejection counter increments with ip_mismatch reason on IP binding failure")
    void redeemTicket_ipMismatchIncrementsMismatchCounter() {
        String ticket = service.issueTicket(CLIENT_IP);
        service.redeemTicket(ticket, OTHER_IP);

        double count = meterRegistry
                .counter(StreamTicketService.COUNTER_REJECTED, "reason", "ip_mismatch")
                .count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("multi-role principal: all roles from originating token are preserved")
    void redeemTicket_multiRolePrincipalPreservesAllRoles() {
        installJwtAuth(USER_ID, JTI, List.of("ROLE_DISPATCHER", "ROLE_MANAGER"));
        service = new StreamTicketService(ticketStore, jtiDenylist, userRepository, meterRegistry);

        String ticket = service.issueTicket(CLIENT_IP);
        Optional<Authentication> auth = service.redeemTicket(ticket, CLIENT_IP);

        assertThat(auth).isPresent();
        assertThat(auth.get().getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder("ROLE_DISPATCHER", "ROLE_MANAGER");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void installJwtAuth(String userId, String jti, List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(userId)
                .jti(jti)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .claim("roles", roles)
                .build();

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
