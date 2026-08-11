package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.identity.domain.LoginAudit;
import com.fieldservice.identity.domain.LoginAuditRepository;
import com.fieldservice.identity.domain.LoginOutcome;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.AuthDependencyUnavailableException;
import com.fieldservice.platform.api.exception.InvalidCredentialsException;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the full login flow: lockout check, credential verification,
 * role validation, token issuance, audit logging, and outbox event publication.
 *
 * <p>All paths (success and failure) write a {@link LoginAudit} row and publish
 * a domain event in the same transaction so authentication history is immutable.
 *
 * <p>Constant-work guarantee: when the email is unknown a BCrypt verification is
 * performed against a pre-computed dummy hash so the CPU cost and latency profile
 * are indistinguishable from the wrong-password path. Locked accounts skip BCrypt
 * to prevent amplification attacks.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private final AppUserRepository            userRepository;
    private final RoleAssignmentRepository     roleRepository;
    private final LoginAuditRepository         auditRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final RefreshTokenRepository       refreshTokenRepository;
    private final PasswordEncoder              passwordEncoder;
    private final String                       dummyPasswordHash;
    private final LoginAttemptTracker          lockoutTracker;
    private final TokenIssuer                  tokenIssuer;
    private final DomainEventPublisher         eventPublisher;

    public LoginService(AppUserRepository userRepository,
                        RoleAssignmentRepository roleRepository,
                        LoginAuditRepository auditRepository,
                        RefreshTokenFamilyRepository familyRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        String dummyPasswordHash,
                        LoginAttemptTracker lockoutTracker,
                        TokenIssuer tokenIssuer,
                        DomainEventPublisher eventPublisher) {
        this.userRepository       = userRepository;
        this.roleRepository       = roleRepository;
        this.auditRepository      = auditRepository;
        this.familyRepository     = familyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder      = passwordEncoder;
        this.dummyPasswordHash    = dummyPasswordHash;
        this.lockoutTracker       = lockoutTracker;
        this.tokenIssuer          = tokenIssuer;
        this.eventPublisher       = eventPublisher;
    }

    /**
     * Container for a successful login result: issued tokens plus user context for
     * the response body. Roles are embedded in the JWT and returned here for convenience.
     */
    public record LoginResult(TokenIssuer.TokenBundle tokens, AppUser user, List<AppRole> roles) {}

    /**
     * Authenticates a user and returns the issued tokens plus user context.
     *
     * @param email     raw email from the request (will be canonicalized)
     * @param password  raw password from the request
     * @param clientIp  remote IP for audit logging
     * @return login result on success
     * @throws InvalidCredentialsException       on any authentication failure
     * @throws AuthDependencyUnavailableException when the lockout store is unavailable
     */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AuthDependencyUnavailableException.class})
    public LoginResult login(String email, String password, String clientIp) {
        String canonical  = email.strip().toLowerCase();
        String emailHash  = sha256Hex(canonical);
        String traceId    = resolveTraceId();

        // --- Step 1: Lockout check (no BCrypt for locked accounts) ---------------
        // Fail-closed: Redis unavailable → 503 (never permit unlimited guessing)
        try {
            if (lockoutTracker.isLocked(emailHash)) {
                writeAudit(emailHash, null, LoginOutcome.LOCKED, traceId, clientIp);
                publishEvent(emailHash, null, LoginOutcome.LOCKED, traceId);
                throw new InvalidCredentialsException("locked");
            }
        } catch (InvalidCredentialsException e) {
            throw e;
        } catch (Exception e) {
            log.error("lockout_store_unavailable trace_id={}", traceId, e);
            writeAudit(emailHash, null, LoginOutcome.LOCKOUT_STORE_UNAVAILABLE, traceId, clientIp);
            publishEvent(emailHash, null, LoginOutcome.LOCKOUT_STORE_UNAVAILABLE, traceId);
            throw new AuthDependencyUnavailableException("lockout-store", e);
        }

        // --- Step 2: Lookup user ------------------------------------------------
        Optional<AppUser> userOpt = userRepository.findByEmail(canonical);

        // --- Step 3: Password verification (constant-work for all paths) --------
        boolean verified;
        AppUser user = null;

        if (userOpt.isEmpty()) {
            // Unknown email: verify against dummy hash to match CPU cost of wrong-password path
            passwordEncoder.matches(password, dummyPasswordHash);
            recordFailure(emailHash, traceId);
            writeAudit(emailHash, null, LoginOutcome.UNKNOWN_EMAIL, traceId, clientIp);
            publishEvent(emailHash, null, LoginOutcome.UNKNOWN_EMAIL, traceId);
            throw new InvalidCredentialsException("unknown-email");
        }

        user = userOpt.get();
        String storedHash = user.getPasswordHash();

        if (storedHash == null) {
            // Federation/unset password: verify against dummy for constant timing
            passwordEncoder.matches(password, dummyPasswordHash);
            verified = false;
        } else {
            verified = passwordEncoder.matches(password, storedHash);
        }

        // --- Step 4: Inactive check --------------------------------------------
        if (!user.getActive()) {
            recordFailure(emailHash, traceId);
            writeAudit(emailHash, user.getId(), LoginOutcome.INACTIVE, traceId, clientIp);
            publishEvent(emailHash, user.getId(), LoginOutcome.INACTIVE, traceId);
            throw new InvalidCredentialsException("inactive");
        }

        // --- Step 5: Wrong password -------------------------------------------
        if (!verified) {
            recordFailure(emailHash, traceId);
            writeAudit(emailHash, user.getId(), LoginOutcome.WRONG_PASSWORD, traceId, clientIp);
            publishEvent(emailHash, user.getId(), LoginOutcome.WRONG_PASSWORD, traceId);
            throw new InvalidCredentialsException("wrong-password");
        }

        // --- Step 6: Role check -----------------------------------------------
        List<RoleAssignment> grants = roleRepository.findByUserId(user.getId());
        if (grants.isEmpty()) {
            log.warn("grantless_user_login_attempt user_id={} trace_id={}",
                    user.getId(), traceId);
            recordFailure(emailHash, traceId);
            writeAudit(emailHash, user.getId(), LoginOutcome.GRANTLESS, traceId, clientIp);
            publishEvent(emailHash, user.getId(), LoginOutcome.GRANTLESS, traceId);
            throw new InvalidCredentialsException("grantless");
        }

        List<AppRole> roles = grants.stream().map(RoleAssignment::getRoleName).toList();

        // --- Step 7: Issue tokens --------------------------------------------
        TokenIssuer.TokenBundle tokens = tokenIssuer.issue(user, roles);

        // --- Step 8: Persist refresh token family ----------------------------
        RefreshTokenFamily family = RefreshTokenFamily.open(user);
        familyRepository.save(family);

        String tokenHash  = sha256Hex(tokens.refreshHandle());
        Instant expiresAt = Instant.now().plus(REFRESH_TOKEN_TTL);
        RefreshToken refreshToken = RefreshToken.issue(family, tokenHash, expiresAt);
        refreshTokenRepository.save(refreshToken);

        // --- Step 9: Audit + event -------------------------------------------
        writeAudit(emailHash, user.getId(), LoginOutcome.SUCCESS, traceId, clientIp);
        publishEvent(emailHash, user.getId(), LoginOutcome.SUCCESS, traceId);

        // --- Step 10: Clear lockout counter ----------------------------------
        try {
            lockoutTracker.recordSuccess(emailHash);
        } catch (Exception e) {
            log.warn("lockout_counter_clear_failed trace_id={}", traceId, e);
        }

        log.info("login_success user_id={} roles={} trace_id={}",
                user.getId(), roles, traceId);

        return new LoginResult(tokens, user, roles);
    }

    // ---- Helpers ---------------------------------------------------------------

    private void writeAudit(String emailHash, java.util.UUID userId, LoginOutcome outcome,
                             String traceId, String clientIp) {
        auditRepository.save(
                LoginAudit.record(emailHash, userId, outcome, traceId, clientIp));
    }

    private void publishEvent(String emailHash, java.util.UUID userId, LoginOutcome outcome,
                               String traceId) {
        LoginAuditPayload payload = new LoginAuditPayload(emailHash, userId, outcome.name());
        DomainEvent event = new DomainEvent(
                UuidV7.generate(),
                "LOGIN_ATTEMPT",
                "IDENTITY",
                userId,
                Instant.now(),
                traceId,
                userId,
                payload);
        eventPublisher.publish(event);
    }

    private void recordFailure(String emailHash, String traceId) {
        try {
            lockoutTracker.recordFailure(emailHash);
        } catch (Exception e) {
            log.warn("lockout_counter_increment_failed trace_id={}", traceId, e);
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
        return (id != null && !id.isBlank()) ? id : java.util.UUID.randomUUID().toString();
    }

    /**
     * Minimal audit payload — email hash only, never the raw email or credentials.
     */
    record LoginAuditPayload(String emailHash, java.util.UUID userId, String outcome) {}
}
