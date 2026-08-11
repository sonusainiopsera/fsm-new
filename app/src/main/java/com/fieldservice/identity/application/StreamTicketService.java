package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.identity.token.StreamTicketStore;
import com.fieldservice.platform.api.exception.AuthDependencyUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues and redeems single-use IP-bound SSE stream tickets.
 *
 * <p>Issuance generates a 256-bit SecureRandom opaque value, stores a SHA-256-keyed
 * Redis hash with a 60-second TTL, and returns the plaintext value once. The value is
 * never logged, never re-read from Redis (the key holds only the hash), and must never
 * appear in any error response or metric label.
 *
 * <p>Redemption is atomic: {@link StreamTicketStore#redeem} fetches and deletes in one
 * Lua round-trip so two concurrent redemptions yield exactly one success.
 *
 * <p>Fail-closed: when the ticket store is unavailable, issuance throws
 * {@link AuthDependencyUnavailableException} (→ 503) and redemption throws
 * {@link StreamTicketRedeemException} (→ 401 via the stream filter chain).
 */
@Service
public class StreamTicketService {

    private static final Logger log = LoggerFactory.getLogger(StreamTicketService.class);
    private static final String METRIC_NAME     = "security.stream.ticket";
    static final int            TICKET_EXPIRES_IN = 60;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StreamTicketStore  ticketStore;
    private final JtiDenylist        jtiDenylist;
    private final AppUserRepository  userRepository;
    private final MeterRegistry      meterRegistry;
    private final Clock              clock;

    /**
     * Uses {@code List<T>} injection so Spring gracefully provides an empty list when the
     * {@link StreamTicketStore} or {@link JtiDenylist} beans are absent (Redis unavailable).
     */
    public StreamTicketService(
            List<StreamTicketStore> ticketStores,
            List<JtiDenylist> denylistBeans,
            AppUserRepository userRepository,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.ticketStore    = ticketStores.isEmpty()  ? null : ticketStores.get(0);
        this.jtiDenylist    = denylistBeans.isEmpty() ? null : denylistBeans.get(0);
        this.userRepository = userRepository;
        this.meterRegistry  = meterRegistry;
        this.clock          = clock;
    }

    /**
     * Issues a single-use ticket for the given authenticated principal bound to {@code clientIp}.
     *
     * @throws AuthDependencyUnavailableException when the ticket store (Redis) is unavailable
     */
    public StreamTicketResult issue(Authentication authentication, String clientIp) {
        if (ticketStore == null) {
            meterRegistry.counter(METRIC_NAME, "action", "issue_store_unavailable").increment();
            throw new AuthDependencyUnavailableException("stream-ticket-store", null);
        }

        String userId      = authentication.getName();
        String jti         = extractJti(authentication);
        String authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        byte[] bytes = new byte[32]; // 256 bits
        SECURE_RANDOM.nextBytes(bytes);
        String ticketValue = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant issuedAt = clock.instant();
        StreamTicketStore.TicketPayload payload = new StreamTicketStore.TicketPayload(
                userId, authorities, clientIp, issuedAt.toString(), jti);

        try {
            ticketStore.store(ticketValue, payload);
        } catch (Exception e) {
            log.error("stream_ticket_store_error client_ip={}", sanitizeIp(clientIp), e);
            meterRegistry.counter(METRIC_NAME, "action", "issue_store_error").increment();
            throw new AuthDependencyUnavailableException("stream-ticket-store", e);
        }

        meterRegistry.counter(METRIC_NAME, "action", "issued").increment();
        return new StreamTicketResult(ticketValue, TICKET_EXPIRES_IN,
                issuedAt.plusSeconds(TICKET_EXPIRES_IN));
    }

    /**
     * Redeems a ticket atomically, validates IP binding, JTI denylist and account active state,
     * and returns an {@link Authentication} carrying the originating authorities.
     *
     * <p>All rejection paths collapse to {@link StreamTicketRedeemException} so callers
     * cannot distinguish expired from replayed from IP-mismatched — generic 401 contract.
     */
    public Authentication redeem(String ticketValue, String clientIp) {
        if (ticketStore == null) {
            meterRegistry.counter(METRIC_NAME, "action", "redeem_store_unavailable").increment();
            throw new StreamTicketRedeemException("store unavailable");
        }

        Optional<StreamTicketStore.TicketPayload> payloadOpt;
        try {
            payloadOpt = ticketStore.redeem(ticketValue);
        } catch (Exception e) {
            log.error("stream_ticket_redeem_store_error client_ip={}", sanitizeIp(clientIp), e);
            meterRegistry.counter(METRIC_NAME, "action", "redeem_store_error").increment();
            throw new StreamTicketRedeemException("store error");
        }

        if (payloadOpt.isEmpty()) {
            // Expired or already consumed — security-relevant but not an IP-mismatch audit event
            log.warn("stream_ticket_not_found client_ip={}", sanitizeIp(clientIp));
            meterRegistry.counter(METRIC_NAME, "action", "redeem_not_found").increment();
            throw new StreamTicketRedeemException("ticket not found");
        }

        StreamTicketStore.TicketPayload payload = payloadOpt.get();

        if (!clientIp.equals(payload.clientIp())) {
            // IP mismatch is security-audit-relevant; log separately
            log.warn("stream_ticket_ip_mismatch issued_to_ip_prefix={} actual_ip={}",
                    sanitizeIp(payload.clientIp()), sanitizeIp(clientIp));
            meterRegistry.counter(METRIC_NAME, "action", "redeem_ip_mismatch").increment();
            throw new StreamTicketRedeemException("ip mismatch");
        }

        if (jtiDenylist != null && !payload.jti().isBlank()) {
            try {
                if (jtiDenylist.isDenied(payload.jti())) {
                    log.warn("stream_ticket_jti_denylisted client_ip={}", sanitizeIp(clientIp));
                    meterRegistry.counter(METRIC_NAME, "action", "redeem_jti_denylisted").increment();
                    throw new StreamTicketRedeemException("token revoked");
                }
            } catch (JtiDenylist.JtiDenylistUnavailableException e) {
                log.error("stream_ticket_jti_denylist_unavailable fail_closed client_ip={}",
                        sanitizeIp(clientIp), e);
                meterRegistry.counter(METRIC_NAME, "action", "redeem_denylist_unavailable").increment();
                throw new StreamTicketRedeemException("denylist unavailable");
            }
        }

        UUID userId = UUID.fromString(payload.userId());
        Optional<AppUser> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty() || !Boolean.TRUE.equals(userOpt.get().getActive())) {
            log.warn("stream_ticket_account_inactive user_id={}", userId);
            meterRegistry.counter(METRIC_NAME, "action", "redeem_account_inactive").increment();
            throw new StreamTicketRedeemException("account inactive");
        }

        List<GrantedAuthority> grantedAuthorities = Arrays.stream(payload.authorities().split(","))
                .filter(s -> !s.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        meterRegistry.counter(METRIC_NAME, "action", "redeemed").increment();
        return UsernamePasswordAuthenticationToken.authenticated(
                payload.userId(), null, grantedAuthorities);
    }

    private static String extractJti(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String jti = jwtAuth.getToken().getId();
            return jti != null ? jti : "";
        }
        return "";
    }

    /** Returns only the first segment of the IP to avoid logging PII while aiding diagnosis. */
    private static String sanitizeIp(String ip) {
        if (ip == null || ip.isBlank()) return "unknown";
        int dot   = ip.indexOf('.');
        int colon = ip.indexOf(':');
        int cut   = dot > 0 ? dot : (colon > 0 ? colon : ip.length());
        return ip.substring(0, cut) + ".*";
    }

    public record StreamTicketResult(String ticketValue, int expiresIn, Instant expiresAt) {}

    /**
     * Thrown when redemption fails for any reason.
     * All causes are collapsed so callers cannot distinguish them (generic 401 contract).
     */
    public static class StreamTicketRedeemException extends RuntimeException {
        StreamTicketRedeemException(String internalReason) {
            // Suppressed stack trace — reason is for internal logging only, never returned to clients
            super(internalReason, null, true, false);
        }
    }
}
