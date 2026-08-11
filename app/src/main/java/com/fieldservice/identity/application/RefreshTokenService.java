package com.fieldservice.identity.application;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.config.AuthProperties;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles refresh-token rotation with reuse detection.
 *
 * <h3>Atomicity</h3>
 * Rotation is performed as a single conditional UPDATE:
 * {@code UPDATE refresh_token SET consumed_at = now() WHERE token_hash = :hash AND consumed_at IS NULL}.
 * An affected-row count of 0 means the handle was already consumed (reuse) or unknown.
 * This avoids a read-then-write race without escalating isolation level.
 *
 * <h3>Reuse detection</h3>
 * When a consumed token is presented again, the entire family is revoked and a
 * {@code RefreshTokenReuseDetected} security event is published through the transactional
 * outbox. Subsequent reuse attempts on an already-revoked family are deduplicated: only
 * a counter is incremented, not a new critical event.
 *
 * <h3>Security invariants</h3>
 * <ul>
 *   <li>Plaintext handles never reach the database — only SHA-256 hex hashes are stored.</li>
 *   <li>Rotation never extends the family absolute expiry.</li>
 *   <li>All expiry comparisons use database time via {@code now()} in native queries.</li>
 * </ul>
 *
 * <p><strong>RESTRICTED:</strong> No handle material may appear in log lines or event payloads.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final String REVOKE_REASON_REPLAY = "REPLAY_ATTACK";
    private static final String REVOKE_REASON_EXPIRED = "EXPIRED";

    private final RefreshTokenRepository tokenRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final AppUserRepository userRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final TokenIssuer tokenIssuer;
    private final SecurityEventPublisher securityEventPublisher;
    private final AuthProperties authProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokenRepository,
                               RefreshTokenFamilyRepository familyRepository,
                               AppUserRepository userRepository,
                               RoleAssignmentRepository roleAssignmentRepository,
                               TokenIssuer tokenIssuer,
                               SecurityEventPublisher securityEventPublisher,
                               AuthProperties authProperties) {
        this.tokenRepository = tokenRepository;
        this.familyRepository = familyRepository;
        this.userRepository = userRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.tokenIssuer = tokenIssuer;
        this.securityEventPublisher = securityEventPublisher;
        this.authProperties = authProperties;
    }

    /**
     * Attempts to rotate the supplied refresh handle.
     *
     * @param rawHandle  opaque 256-bit handle from the HttpOnly cookie
     * @param traceId    request trace identifier for audit records
     * @param clientIp   client IP address (for security event payload only)
     * @param userAgent  User-Agent header value (for security event payload only)
     * @return rotation result — success carries new access token and rotated handle;
     *         any failure collapses to the same 401 shape at the HTTP layer
     */
    @Transactional
    public RotationResult rotate(String rawHandle, String traceId,
                                 String clientIp, String userAgent) {
        String hash = sha256Hex(rawHandle);

        // Atomic conditional consume — avoids read-then-write race.
        int affected = tokenRepository.consumeByTokenHash(hash);

        if (affected == 0) {
            return handleConsumeFailure(hash, traceId, clientIp, userAgent);
        }

        // Token was just consumed; load it and validate the family.
        RefreshToken token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalStateException(
                        "Token disappeared after consume; familyId unknown"));

        return handleConsumeSuccess(token, traceId, clientIp, userAgent);
    }

    // -----------------------------------------------------------------------
    // Internal — consume failed (0 rows updated)
    // -----------------------------------------------------------------------

    private RotationResult handleConsumeFailure(String hash, String traceId,
                                                String clientIp, String userAgent) {
        Optional<RefreshToken> tokenOpt = tokenRepository.findByTokenHash(hash);
        if (tokenOpt.isEmpty()) {
            // Unknown hash — return generic 401, no family to revoke
            log.debug("auth.refresh.unknown_hash traceId={}", traceId);
            return RotationResult.failure(RotationResult.FailureReason.UNKNOWN_HANDLE);
        }

        RefreshToken consumed = tokenOpt.get();
        RefreshTokenFamily family = familyRepository.findById(consumed.getFamilyId())
                .orElseThrow(() -> new IllegalStateException("Family missing for consumed token"));

        if (family.isRevoked()) {
            // Already-revoked family — deduplicate: counter only, no new critical event
            securityEventPublisher.incrementReuseCounter(family.getId(), traceId);
            return RotationResult.failure(RotationResult.FailureReason.REVOKED_FAMILY);
        }

        // First reuse detection: revoke the entire family and publish security event
        family.revoke(Instant.now(), REVOKE_REASON_REPLAY);
        familyRepository.save(family);

        securityEventPublisher.publishReuseDetected(
                family.getUserId(), family.getId(), traceId, clientIp, userAgent);

        log.warn("audit.refresh actorId={} familyId={} resource=RefreshTokenFamily " +
                        "operation=ROTATE outcome=REUSE_DETECTED traceId={}",
                family.getUserId(), family.getId(), traceId);

        return RotationResult.failure(RotationResult.FailureReason.REUSE_DETECTED);
    }

    // -----------------------------------------------------------------------
    // Internal — consume succeeded (1 row updated)
    // -----------------------------------------------------------------------

    private RotationResult handleConsumeSuccess(RefreshToken token, String traceId,
                                                String clientIp, String userAgent) {
        RefreshTokenFamily family = familyRepository.findById(token.getFamilyId())
                .orElseThrow(() -> new IllegalStateException("Family missing for token"));

        if (family.isRevoked()) {
            // Family was concurrently revoked (e.g. parallel reuse detection)
            log.warn("audit.refresh familyId={} outcome=REVOKED_CONCURRENT traceId={}",
                    family.getId(), traceId);
            return RotationResult.failure(RotationResult.FailureReason.REVOKED_FAMILY);
        }

        Instant now = Instant.now();
        if (family.isAbsolutelyExpired(now)) {
            // Mark the family expired for operational visibility (first time only)
            if (!family.isRevoked()) {
                family.revoke(now, REVOKE_REASON_EXPIRED);
                familyRepository.save(family);
            }
            log.info("audit.refresh familyId={} userId={} outcome=EXPIRED traceId={}",
                    family.getId(), family.getUserId(), traceId);
            return RotationResult.failure(RotationResult.FailureReason.EXPIRED_FAMILY);
        }

        Optional<AppUser> userOpt = userRepository.findById(family.getUserId());
        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            log.info("audit.refresh familyId={} userId={} outcome=INACTIVE_USER traceId={}",
                    family.getId(), family.getUserId(), traceId);
            return RotationResult.failure(RotationResult.FailureReason.INACTIVE_USER);
        }

        AppUser user = userOpt.get();
        List<RoleAssignment> grants = roleAssignmentRepository.findByUserId(user.getId());
        List<String> roles = grants.stream().map(g -> g.getRoleName().name()).toList();

        // Issue new handle — 256-bit random, base64url without padding
        byte[] handleBytes = new byte[32];
        secureRandom.nextBytes(handleBytes);
        String newHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(handleBytes);
        String newHash = sha256Hex(newHandle);

        // Persist the rotated token under the same family with the SAME absolute expiry
        tokenRepository.save(new RefreshToken(
                family.getId(), newHash, now, family.getAbsoluteExpiresAt()));

        String accessToken = tokenIssuer.issueAccessToken(user.getId(), user.getEmail(), roles);

        log.info("audit.refresh actorId={} familyId={} resource=RefreshTokenFamily " +
                        "operation=ROTATE outcome=SUCCESS traceId={}",
                user.getId(), family.getId(), traceId);

        return RotationResult.success(accessToken, newHandle,
                authProperties.jwt().accessTokenTtlSeconds());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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

    /** Sealed result of a rotation attempt. */
    public sealed interface RotationResult {

        record Success(String accessToken, String refreshHandle, long expiresIn)
                implements RotationResult {}

        record Failure(FailureReason reason) implements RotationResult {}

        enum FailureReason {
            UNKNOWN_HANDLE,
            REUSE_DETECTED,
            REVOKED_FAMILY,
            EXPIRED_FAMILY,
            INACTIVE_USER,
            MALFORMED_HANDLE
        }

        static RotationResult success(String accessToken, String refreshHandle, long expiresIn) {
            return new Success(accessToken, refreshHandle, expiresIn);
        }

        static RotationResult failure(FailureReason reason) {
            return new Failure(reason);
        }
    }
}
