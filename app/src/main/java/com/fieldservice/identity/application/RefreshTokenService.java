package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.platform.api.exception.InvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orchestrates refresh-token rotation with family-level reuse detection.
 *
 * <p>Rotation is atomic: a conditional {@code UPDATE consumed_at} whose affected-row count
 * distinguishes legitimate use (1 row) from reuse or unknown handles (0 rows). This avoids
 * a read-then-write race without escalating isolation and cannot deadlock.
 *
 * <p>Reuse response: revoke the entire family (not just the presented handle), publish a
 * critical {@code REFRESH_TOKEN_REUSE} security event via the transactional outbox, and
 * return 401. The revocation and SIEM event commit in the same transaction as the reuse
 * detection — they cannot diverge on crash.
 *
 * <p>SIEM deduplication: only the first reuse on a given family emits a critical event.
 * Subsequent reuse attempts on an already-revoked family are logged at warn level only.
 *
 * <p>No handle or hash material ever appears in log output, response bodies, or event
 * payloads. Only the family identifier appears in telemetry.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    // A valid refresh handle is exactly 43 base64url characters (32 bytes without padding).
    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final RefreshTokenRepository       tokenRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final RoleAssignmentRepository     roleRepository;
    private final TokenIssuer                  tokenIssuer;
    private final SecurityEventPublisher       securityEventPublisher;
    private final Clock                        clock;

    public RefreshTokenService(RefreshTokenRepository tokenRepository,
                               RefreshTokenFamilyRepository familyRepository,
                               RoleAssignmentRepository roleRepository,
                               TokenIssuer tokenIssuer,
                               SecurityEventPublisher securityEventPublisher,
                               Clock clock) {
        this.tokenRepository       = tokenRepository;
        this.familyRepository      = familyRepository;
        this.roleRepository        = roleRepository;
        this.tokenIssuer           = tokenIssuer;
        this.securityEventPublisher = securityEventPublisher;
        this.clock                 = clock;
    }

    /**
     * Container for a successful rotation: the new token bundle and resolved roles
     * so the controller can set the cookie and build the response body.
     */
    public record RotationResult(TokenIssuer.TokenBundle tokens) {}

    /**
     * Rotates the refresh handle identified by {@code rawHandle}.
     *
     * @param rawHandle  the raw base64url handle from the HttpOnly cookie
     * @param clientIp   client IP for the security event payload
     * @param userAgent  User-Agent header value for the security event payload
     * @return rotation result containing new tokens on success
     * @throws InvalidCredentialsException for any failure — all collapse to 401
     */
    @Transactional
    public RotationResult rotate(String rawHandle, String clientIp, String userAgent) {
        String traceId = resolveTraceId();

        // 1. Validate format before any DB access (guards against malformed values)
        validateHandleFormat(rawHandle);

        // 2. Hash the handle — only the hash touches the DB
        String tokenHash = sha256Hex(rawHandle);

        // 3. Atomic consume: 1 row = valid, 0 rows = reuse / unknown / already-revoked
        int affected = tokenRepository.consumeToken(tokenHash);

        if (affected == 0) {
            handleZeroRowsAffected(tokenHash, clientIp, userAgent, traceId);
            throw new InvalidCredentialsException("reauthentication-required");
        }

        // 4. Load the freshly consumed token (EntityManager cache was cleared by @Modifying)
        RefreshToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidCredentialsException("reauthentication-required"));

        RefreshTokenFamily family = token.getFamily();
        AppUser user = family.getUser();

        // 5. Racing revocation guard: a concurrent reuse on a sibling token may have
        //    already revoked this family between our UPDATE and this load
        if (family.isRevoked()) {
            log.warn("refresh_rotate_family_revoked_race family_id={} trace_id={}",
                    family.getId(), traceId);
            throw new InvalidCredentialsException("reauthentication-required");
        }

        // 6. Absolute expiry check — family.getExpiresAt() is a DB-stored timestamp;
        //    comparing with clock.instant() is safe because all instances see the same row
        Instant now = clock.instant();
        if (now.isAfter(family.getExpiresAt())) {
            log.warn("refresh_rotate_family_expired family_id={} trace_id={}",
                    family.getId(), traceId);
            throw new InvalidCredentialsException("reauthentication-required");
        }

        // 7. Deactivated-owner guard
        if (!Boolean.TRUE.equals(user.getActive())) {
            log.warn("refresh_rotate_user_deactivated user_id={} trace_id={}",
                    user.getId(), traceId);
            throw new InvalidCredentialsException("reauthentication-required");
        }

        // 8. Role check — grantless users must not receive a new token
        List<RoleAssignment> grants = roleRepository.findByUserId(user.getId());
        if (grants.isEmpty()) {
            log.warn("refresh_rotate_grantless user_id={} trace_id={}", user.getId(), traceId);
            throw new InvalidCredentialsException("reauthentication-required");
        }
        List<AppRole> roles = grants.stream().map(RoleAssignment::getRoleName).toList();

        // 9. Issue new tokens (15-min access + fresh 256-bit refresh handle)
        TokenIssuer.TokenBundle tokens = tokenIssuer.issue(user, roles);
        String newHash = sha256Hex(tokens.refreshHandle());

        // 10. Persist new token under the same family using the ORIGINAL absolute expiry
        //     (rotation must never extend the family beyond its original 7-day window)
        RefreshToken newToken = RefreshToken.issue(family, newHash, family.getExpiresAt());
        tokenRepository.save(newToken);

        // 11. Publish audit event (no handle or hash in payload)
        securityEventPublisher.publishRotationSuccess(family, user.getId(), traceId);

        log.info("refresh_rotate_success user_id={} family_id={} trace_id={}",
                user.getId(), family.getId(), traceId);

        return new RotationResult(tokens);
    }

    /**
     * Handles the 0-affected-rows case after the conditional UPDATE.
     * Classifies as reuse (token consumed), already-revoked family (dedup), or unknown handle.
     */
    private void handleZeroRowsAffected(String tokenHash, String clientIp,
                                        String userAgent, String traceId) {
        Optional<RefreshToken> tokenOpt = tokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            // Hash not in DB — unknown handle; nothing to revoke
            log.warn("refresh_unknown_handle trace_id={}", traceId);
            return;
        }

        RefreshToken token = tokenOpt.get();
        RefreshTokenFamily family = token.getFamily();

        if (family.isRevoked()) {
            // Family was already revoked (first reuse already processed). Dedup: no new event.
            log.warn("refresh_reuse_already_revoked family_id={} trace_id={}",
                    family.getId(), traceId);
            return;
        }

        // First reuse detection: revoke family + all tokens, publish critical SIEM event
        log.warn("refresh_token_reuse_detected family_id={} user_id={} trace_id={}",
                family.getId(), family.getUser().getId(), traceId);

        String reason = "REFRESH_TOKEN_REUSE";
        family.revoke(reason);
        familyRepository.save(family);

        List<RefreshToken> allTokens = tokenRepository.findByFamilyId(family.getId());
        for (RefreshToken t : allTokens) {
            if (!t.isRevoked()) {
                t.revoke(reason);
            }
        }
        tokenRepository.saveAll(allTokens);

        // At-least-once delivery: SIEM event commits with the revocation or not at all
        securityEventPublisher.publishReuseDetected(family, clientIp, userAgent, traceId);
    }

    private static void validateHandleFormat(String rawHandle) {
        if (rawHandle == null || !HANDLE_PATTERN.matcher(rawHandle).matches()) {
            throw new InvalidCredentialsException("malformed-handle");
        }
    }

    public static String sha256Hex(String input) {
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
