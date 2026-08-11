package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.outbox.payload.LogoutPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles the logout sequence: revoke the refresh-token family then denylist the access token.
 *
 * <h3>Ordering guarantee</h3>
 * Family revocation runs inside a dedicated committed transaction via {@link TransactionTemplate}.
 * The jti denylist write happens after the transaction commits so that a denylist failure never
 * rolls back the revocation — a revoked family plus a briefly live short-lived token is strictly
 * safer than the inverse.
 *
 * <h3>Idempotency</h3>
 * All not-found and already-revoked paths return normally. Double-logout, cookie-less logout,
 * and logout against an expired or unknown family all return 204 at the HTTP layer.
 *
 * <h3>Security invariants</h3>
 * <ul>
 *   <li>No token, handle, hash, or derived secret appears in any log line or event payload.</li>
 *   <li>The denylist TTL is set to the token residual lifetime and never exceeds it.</li>
 *   <li>Revocation scope is limited to the presented family — other device sessions are unaffected.</li>
 * </ul>
 */
@Service
public class LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);
    private static final String REVOKE_REASON = "USER_LOGOUT";

    private final RefreshTokenRepository tokenRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final JtiDenylist jtiDenylist;
    private final DomainEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;

    public LogoutService(RefreshTokenRepository tokenRepository,
                         RefreshTokenFamilyRepository familyRepository,
                         JtiDenylist jtiDenylist,
                         DomainEventPublisher eventPublisher,
                         MeterRegistry meterRegistry,
                         TransactionTemplate txTemplate) {
        this.tokenRepository = tokenRepository;
        this.familyRepository = familyRepository;
        this.jtiDenylist = jtiDenylist;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.txTemplate = txTemplate;
    }

    /**
     * Logs out by revoking the refresh-token family and denylisting the access token jti.
     *
     * @param rawHandle  raw 256-bit refresh handle from the HttpOnly cookie; null → no-op family step
     * @param jti        access token jti claim; null → denylist write skipped
     * @param jwtExp     access token expiration instant; null or past → denylist write skipped
     * @param traceId    request trace identifier included in the audit record
     * @return {@link LogoutResult.Success} on clean logout, or {@link LogoutResult.DenylistFailure}
     *         when the family was revoked but the denylist store is unreachable
     */
    public LogoutResult logout(String rawHandle, String jti, Instant jwtExp, String traceId) {
        // Step 1: Revoke the refresh family inside a committed transaction.
        // Returns the actorId if a family was found; null for all no-op cases.
        UUID actorId = txTemplate.execute(status -> revokeFamily(rawHandle, traceId));

        // Step 2: Write the jti denylist entry outside the transaction so that a Redis
        // failure cannot roll back the already-committed family revocation.
        if (jti != null && jwtExp != null) {
            Duration residualTtl = computeResidualTtl(jwtExp);
            if (residualTtl.toSeconds() > 0) {
                try {
                    jtiDenylist.revoke(jti, residualTtl);
                    log.debug("auth.logout.jti_denylisted traceId={} residualTtlSeconds={}",
                            traceId, residualTtl.toSeconds());
                } catch (JtiDenylist.DenylistUnavailableException e) {
                    log.warn("alert.logout_denylist_failure actorId={} traceId={} error={} " +
                             "note='access token may remain live until natural expiry at {}'",
                            actorId, traceId, e.getMessage(), jwtExp);
                    meterRegistry.counter("auth.logout.denylist_insert_failure").increment();
                    return LogoutResult.denylistFailure(traceId);
                }
            } else {
                log.debug("auth.logout.jti_skip_expired traceId={}", traceId);
            }
        }
        return LogoutResult.success();
    }

    // -----------------------------------------------------------------------
    // Transactional family revocation (executed via TransactionTemplate)
    // -----------------------------------------------------------------------

    private UUID revokeFamily(String rawHandle, String traceId) {
        if (rawHandle == null) {
            log.debug("auth.logout.no_cookie traceId={}", traceId);
            return null;
        }

        String hash = sha256Hex(rawHandle);
        Optional<RefreshToken> tokenOpt = tokenRepository.findByTokenHash(hash);
        if (tokenOpt.isEmpty()) {
            log.debug("auth.logout.unknown_handle traceId={}", traceId);
            return null;
        }

        RefreshToken token = tokenOpt.get();
        Optional<RefreshTokenFamily> familyOpt = familyRepository.findById(token.getFamilyId());
        if (familyOpt.isEmpty()) {
            log.debug("auth.logout.family_missing familyId={} traceId={}", token.getFamilyId(), traceId);
            return null;
        }

        RefreshTokenFamily family = familyOpt.get();

        if (family.isRevoked()) {
            // Idempotent no-op: already revoked by a prior logout or security event.
            log.info("audit.logout actorId={} familyId={} resource=RefreshTokenFamily " +
                     "operation=LOGOUT outcome=ALREADY_REVOKED traceId={}",
                    family.getUserId(), family.getId(), traceId);
            return family.getUserId();
        }

        family.revoke(Instant.now(), REVOKE_REASON);
        familyRepository.save(family);

        // Emit audit event within this transaction (MANDATORY propagation on publisher).
        var payload = PiiRedactionUtility.toPayloadMap(
                new LogoutPayload(family.getUserId(), family.getId(), traceId, "SUCCESS"));
        eventPublisher.publish(DomainEvent.of(
                LogoutPayload.EVENT_TYPE,
                LogoutPayload.AGGREGATE_TYPE,
                family.getId(),
                Instant.now(),
                traceId,
                family.getUserId(),
                payload));

        log.info("audit.logout actorId={} familyId={} resource=RefreshTokenFamily " +
                 "operation=LOGOUT outcome=SUCCESS traceId={}",
                family.getUserId(), family.getId(), traceId);

        return family.getUserId();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Computes the residual token lifetime, floored at zero.
     * Package-private to allow direct unit testing.
     */
    static Duration computeResidualTtl(Instant exp) {
        Duration remaining = Duration.between(Instant.now(), exp);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // -----------------------------------------------------------------------
    // Result types
    // -----------------------------------------------------------------------

    /** Sealed result of a logout attempt. */
    public sealed interface LogoutResult {

        record Success() implements LogoutResult {}

        /** Family was revoked but the jti denylist write failed; token may remain live. */
        record DenylistFailure(String traceId) implements LogoutResult {}

        static LogoutResult success() { return new Success(); }

        static LogoutResult denylistFailure(String traceId) {
            return new DenylistFailure(traceId);
        }
    }
}
