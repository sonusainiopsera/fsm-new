package com.fieldservice.identity.application;

import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.identity.token.StreamTicketPayload;
import com.fieldservice.identity.token.StreamTicketStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and redeems single-use IP-bound SSE stream tickets (WO-115).
 *
 * <h3>Issuance</h3>
 * <ol>
 *   <li>Requires an authenticated principal with a valid {@link JwtAuthenticationToken}.</li>
 *   <li>Generates a 256-bit {@link SecureRandom} ticket value, base64url-encoded without padding.</li>
 *   <li>Stores the SHA-256 hash of the ticket value in the ticket store with a 60-second TTL,
 *       binding it to the userId, authorities, client IP, and JWT ID (jti).</li>
 *   <li>Returns the plaintext ticket value to the caller. The plaintext is never persisted.</li>
 * </ol>
 *
 * <h3>Redemption</h3>
 * <ol>
 *   <li>Hashes the presented ticket value and atomically fetches-and-deletes the Redis entry
 *       so exactly one concurrent redemption succeeds.</li>
 *   <li>Validates: key existence (expired or replayed → 401), IP binding, jti denylist, account active.</li>
 *   <li>Returns an {@link Authentication} carrying the same authorities as the originating token.</li>
 * </ol>
 *
 * <p><strong>RESTRICTED:</strong> The opaque ticket value must never appear in any log line,
 * metric label, or error response. Only the hashed form appears in Redis.
 */
@Service
public class StreamTicketService {

    private static final Logger log = LoggerFactory.getLogger(StreamTicketService.class);

    public static final Duration TICKET_TTL = Duration.ofSeconds(60);
    static final String COUNTER_ISSUED = "stream_ticket.issued";
    static final String COUNTER_REDEEMED = "stream_ticket.redeemed";
    static final String COUNTER_REJECTED = "stream_ticket.rejected";

    private final StreamTicketStore ticketStore;
    private final JtiDenylist jtiDenylist;
    private final AppUserRepository userRepository;
    private final MeterRegistry meterRegistry;
    private final SecureRandom secureRandom = new SecureRandom();

    public StreamTicketService(StreamTicketStore ticketStore,
                               JtiDenylist jtiDenylist,
                               AppUserRepository userRepository,
                               MeterRegistry meterRegistry) {
        this.ticketStore = ticketStore;
        this.jtiDenylist = jtiDenylist;
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;
    }

    // -------------------------------------------------------------------------
    // Issuance
    // -------------------------------------------------------------------------

    /**
     * Issues a single-use stream ticket for the currently authenticated principal.
     *
     * @param clientIp trusted-proxy-resolved client IP of the issuance request
     * @return opaque ticket value (plaintext, 256-bit, base64url-encoded, 43 chars)
     * @throws StreamTicketStore.StoreUnavailableException if the ticket store is unavailable
     * @throws IllegalStateException if the current principal is not a JWT authentication
     */
    @PreAuthorize("isAuthenticated()")
    public String issueTicket(String clientIp) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("Stream ticket issuance requires JWT authentication");
        }

        Jwt jwt = jwtAuth.getToken();
        String userId = jwt.getSubject();
        String jti = jwt.getId();  // may be null for tokens without jti claim
        List<String> authorities = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .toList();

        // Generate 256-bit ticket value
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String ticketValue = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        // Derive the Redis key as SHA-256(ticketValue) so the plaintext is never persisted
        String hashedKey = sha256Hex(ticketValue);

        StreamTicketPayload payload = new StreamTicketPayload(
                userId,
                authorities,
                clientIp,
                Instant.now(),
                jti != null ? jti : ""
        );

        ticketStore.store(hashedKey, payload, TICKET_TTL);

        counter(COUNTER_ISSUED).increment();
        log.debug("stream_ticket.issued userId={} traceId={}", userId, traceId());

        return ticketValue;
    }

    // -------------------------------------------------------------------------
    // Redemption
    // -------------------------------------------------------------------------

    /**
     * Atomically redeems a stream ticket and returns the resolved principal.
     *
     * <p>All failure modes (expired, replayed, IP mismatch, denylisted jti, inactive account)
     * collapse to {@link Optional#empty()} so callers cannot distinguish failure causes.
     * Security-relevant failures additionally emit a structured log and a tagged metric.
     *
     * @param ticketValue the opaque ticket value presented by the client
     * @param clientIp    trusted-proxy-resolved IP of the redemption request
     * @return resolved {@link Authentication} on success, empty on any rejection
     * @throws StreamTicketStore.StoreUnavailableException if the ticket store is unavailable
     */
    public Optional<Authentication> redeemTicket(String ticketValue, String clientIp) {
        String hashedKey = sha256Hex(ticketValue);

        Optional<StreamTicketPayload> maybePayload = ticketStore.consumeAtomically(hashedKey);

        if (maybePayload.isEmpty()) {
            counter(COUNTER_REJECTED, "reason", "not_found").increment();
            log.warn("stream_ticket.rejected reason=not_found traceId={}", traceId());
            return Optional.empty();
        }

        StreamTicketPayload payload = maybePayload.get();

        // IP binding check
        if (!payload.clientIp().equals(clientIp)) {
            counter(COUNTER_REJECTED, "reason", "ip_mismatch").increment();
            log.warn("security.stream_ticket_ip_mismatch userId={} issuedIp={} redeemIp={} traceId={}",
                    payload.userId(), maskIp(payload.clientIp()), maskIp(clientIp), traceId());
            return Optional.empty();
        }

        // jti denylist check (skip if jti is empty — tickets from tokens without jti claim)
        if (!payload.jti().isEmpty()) {
            try {
                if (jtiDenylist.isRevoked(payload.jti())) {
                    counter(COUNTER_REJECTED, "reason", "denylist").increment();
                    log.warn("stream_ticket.rejected reason=denylist userId={} traceId={}",
                            payload.userId(), traceId());
                    return Optional.empty();
                }
            } catch (JtiDenylist.DenylistUnavailableException e) {
                // Fail-closed: treat denylist unavailability as rejection
                counter(COUNTER_REJECTED, "reason", "denylist_unavailable").increment();
                log.warn("stream_ticket.rejected reason=denylist_unavailable userId={} traceId={}",
                        payload.userId(), traceId());
                return Optional.empty();
            }
        }

        // Account active check
        UUID userId;
        try {
            userId = UUID.fromString(payload.userId());
        } catch (IllegalArgumentException e) {
            counter(COUNTER_REJECTED, "reason", "invalid_userid").increment();
            return Optional.empty();
        }

        boolean userActive = userRepository.findById(userId)
                .map(u -> u.isActive())
                .orElse(false);

        if (!userActive) {
            counter(COUNTER_REJECTED, "reason", "inactive").increment();
            log.warn("stream_ticket.rejected reason=inactive userId={} traceId={}", userId, traceId());
            return Optional.empty();
        }

        // Success: build principal from stored authorities
        List<SimpleGrantedAuthority> grantedAuthorities = payload.authorities().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        Authentication principal = new UsernamePasswordAuthenticationToken(
                payload.userId(), null, grantedAuthorities);

        counter(COUNTER_REDEEMED).increment();
        log.debug("stream_ticket.redeemed userId={} traceId={}", userId, traceId());

        return Optional.of(principal);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String maskIp(String ip) {
        if (ip == null) return "null";
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) {
            return ip.substring(0, lastDot) + ".***";
        }
        // IPv6: mask last segment
        int lastColon = ip.lastIndexOf(':');
        if (lastColon > 0) {
            return ip.substring(0, lastColon) + ":****";
        }
        return "***";
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}
