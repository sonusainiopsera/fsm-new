package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.token.JtiDenylist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orchestrates session logout: revokes the refresh-token family, adds the outstanding
 * access token JTI to the Redis denylist for its residual lifetime, and emits an
 * audit-grade event.
 *
 * <h2>Ordering and failure semantics</h2>
 * <ol>
 *   <li>Family revocation commits inside a transaction — this is the authoritative
 *       termination of the session.</li>
 *   <li>JTI denylist insertion happens <em>after</em> the transaction commits. On
 *       failure the service returns {@link LogoutStatus#REVOKED_DENYLIST_FAILED}; the
 *       committed revocation is not rolled back. The caller returns 503 and logs that
 *       the access token may remain live until natural expiry.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * Missing or already-revoked families, absent cookies, and expired tokens all
 * return {@link LogoutStatus#NO_OP} — never an exception. Double logout is safe.
 *
 * <h2>Audit contract</h2>
 * No token, handle, or hash material appears in log lines, emitted events, or
 * return values. Only the family identifier, userId, and correlation fields are used.
 */
@Service
public class LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

    private static final String LOGOUT_REASON  = "USER_LOGOUT";
    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final RefreshTokenRepository       tokenRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final SecurityEventPublisher       securityEventPublisher;
    private final TransactionTemplate          txTemplate;
    private final JtiDenylist                  jtiDenylist;

    /**
     * JtiDenylist is optional — it is absent when Redis is unavailable (e.g. H2 test
     * profile). Use {@code List<JtiDenylist>} injection to avoid a missing-bean failure.
     */
    public LogoutService(RefreshTokenRepository tokenRepository,
                         RefreshTokenFamilyRepository familyRepository,
                         SecurityEventPublisher securityEventPublisher,
                         TransactionTemplate txTemplate,
                         List<JtiDenylist> denylistBeans) {
        this.tokenRepository        = tokenRepository;
        this.familyRepository       = familyRepository;
        this.securityEventPublisher = securityEventPublisher;
        this.txTemplate             = txTemplate;
        this.jtiDenylist            = denylistBeans.isEmpty() ? null : denylistBeans.get(0);
    }

    /**
     * Outcome of a logout attempt.
     */
    public enum LogoutStatus {
        /** Family revoked and JTI denylisted successfully. */
        REVOKED,
        /** Family revoked but JTI denylist insertion failed; access token may remain live. */
        REVOKED_DENYLIST_FAILED,
        /** Nothing to revoke — idempotent no-op. */
        NO_OP
    }

    /**
     * Performs the full logout sequence.
     *
     * @param rawHandle   opaque refresh handle from the HttpOnly cookie; may be null
     * @param jti         JTI claim from the access token; may be null when no bearer token present
     * @param tokenExpiry {@code exp} claim from the access token; may be null
     * @return the outcome; never throws
     */
    public LogoutStatus logout(String rawHandle, String jti, Instant tokenExpiry) {
        String traceId = resolveTraceId();

        // Step 1: revoke the refresh-token family inside a transaction
        boolean revoked = revokeFamily(rawHandle, traceId);

        if (!revoked) {
            // Still denylist the jti even if no family was found — the access token may be live
            denyJti(jti, tokenExpiry, traceId);
            return LogoutStatus.NO_OP;
        }

        // Step 2: write jti to denylist AFTER the transaction committed
        if (jti != null && !jti.isBlank() && tokenExpiry != null) {
            return denyJti(jti, tokenExpiry, traceId)
                    ? LogoutStatus.REVOKED
                    : LogoutStatus.REVOKED_DENYLIST_FAILED;
        }

        return LogoutStatus.REVOKED;
    }

    // ---- private helpers -------------------------------------------------------

    /**
     * Revokes the family identified by {@code rawHandle} and all its tokens in a
     * single transaction. Returns {@code true} if a family was found and revoked,
     * {@code false} for every no-op path (missing handle, unknown handle,
     * already-revoked family).
     */
    private boolean revokeFamily(String rawHandle, String traceId) {
        if (rawHandle == null || rawHandle.isBlank()) {
            log.info("logout_no_cookie trace_id={}", traceId);
            return false;
        }

        // Loose format check: log malformed handles at warn but treat as no-op
        if (!HANDLE_PATTERN.matcher(rawHandle).matches()) {
            log.warn("logout_malformed_handle_ignored trace_id={}", traceId);
            return false;
        }

        String tokenHash = sha256Hex(rawHandle);

        Boolean revoked = txTemplate.execute(status -> {
            Optional<RefreshToken> tokenOpt = tokenRepository.findByTokenHash(tokenHash);
            if (tokenOpt.isEmpty()) {
                log.info("logout_unknown_handle trace_id={}", traceId);
                return false;
            }

            RefreshToken token = tokenOpt.get();
            RefreshTokenFamily family = token.getFamily();

            if (family.isRevoked()) {
                log.info("logout_family_already_revoked family_id={} trace_id={}",
                        family.getId(), traceId);
                return false;
            }

            UUID userId = family.getUser().getId();

            // Revoke all tokens in the family
            List<RefreshToken> allTokens = tokenRepository.findByFamilyId(family.getId());
            for (RefreshToken t : allTokens) {
                if (!t.isRevoked()) {
                    t.revoke(LOGOUT_REASON);
                }
            }
            tokenRepository.saveAll(allTokens);

            // Revoke the family
            family.revoke(LOGOUT_REASON);
            familyRepository.save(family);

            // Emit audit event — no token material in payload
            securityEventPublisher.publishLogout(family, userId, traceId);

            log.info("logout_family_revoked family_id={} user_id={} trace_id={}",
                    family.getId(), userId, traceId);
            return true;
        });

        return Boolean.TRUE.equals(revoked);
    }

    /**
     * Inserts the JTI into the denylist with its residual TTL.
     * Returns {@code true} on success (or when no denylist is configured), {@code false}
     * on Redis unavailability.
     */
    private boolean denyJti(String jti, Instant tokenExpiry, String traceId) {
        if (jti == null || jti.isBlank() || tokenExpiry == null) {
            return true; // nothing to denylist — not a failure
        }
        if (jtiDenylist == null) {
            return true; // denylist not configured (e.g. test profile without Redis)
        }
        try {
            jtiDenylist.deny(jti, tokenExpiry);
            return true;
        } catch (Exception e) {
            log.error("logout_denylist_insert_failed jti_present=true trace_id={} — "
                    + "access token may remain live until natural expiry; "
                    + "trigger ALERT:logout_denylist_failure", traceId, e);
            return false;
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
