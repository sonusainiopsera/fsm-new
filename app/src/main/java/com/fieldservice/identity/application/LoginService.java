package com.fieldservice.identity.application;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.config.AuthProperties;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.outbox.payload.LoginAttemptedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
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

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final AppUserRepository userRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptTracker attemptTracker;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenFamilyRepository familyRepository;
    private final RefreshTokenRepository tokenRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuthProperties authProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile String dummyHash;

    public LoginService(
            AppUserRepository userRepository,
            RoleAssignmentRepository roleAssignmentRepository,
            PasswordEncoder passwordEncoder,
            LoginAttemptTracker attemptTracker,
            TokenIssuer tokenIssuer,
            RefreshTokenFamilyRepository familyRepository,
            RefreshTokenRepository tokenRepository,
            DomainEventPublisher eventPublisher,
            AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.attemptTracker = attemptTracker;
        this.tokenIssuer = tokenIssuer;
        this.familyRepository = familyRepository;
        this.tokenRepository = tokenRepository;
        this.eventPublisher = eventPublisher;
        this.authProperties = authProperties;
    }

    @PostConstruct
    void initDummyHash() {
        byte[] noise = new byte[32];
        secureRandom.nextBytes(noise);
        dummyHash = passwordEncoder.encode("dummy-" + HexFormat.of().formatHex(noise));
    }

    /**
     * Authenticates a user and issues tokens.
     *
     * <p>All failure paths return an indistinguishable 401 shape and always perform BCrypt
     * verification so response-time distribution does not disclose account existence.
     *
     * @return {@link LoginResult.Success} on authenticated login or {@link LoginResult.Failure} on any rejection
     * @throws LoginAttemptTracker.LoginAttemptStoreException when Redis is unreachable (fail-closed → 503)
     */
    @Transactional
    public LoginResult login(String email, String password) {
        String canonical = email.trim().toLowerCase();
        String emailHash = sha256Hex(canonical);
        String maskedEmail = maskEmail(canonical);

        // Fail-closed lockout check — throws LoginAttemptStoreException → 503 if Redis is down
        int failCount = attemptTracker.getCount(emailHash);
        if (failCount >= authProperties.lockout().maxFailures()) {
            passwordEncoder.matches(password, dummyHash); // constant-work
            auditLog(null, maskedEmail, "LOCKED", 0);
            return LoginResult.failure(LoginResult.FailureReason.INVALID_CREDENTIALS);
        }

        Optional<AppUser> userOpt = userRepository.findByEmailIgnoreCase(canonical);
        String storedHash = userOpt.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, storedHash);

        if (userOpt.isEmpty() || !matches) {
            recordFailureQuietly(emailHash, maskedEmail);
            auditLog(null, maskedEmail, "INVALID_CREDENTIALS", 0);
            return LoginResult.failure(LoginResult.FailureReason.INVALID_CREDENTIALS);
        }

        AppUser user = userOpt.get();

        if (!user.isActive()) {
            recordFailureQuietly(emailHash, maskedEmail);
            auditLog(user.getId(), maskedEmail, "INACTIVE", 0);
            return LoginResult.failure(LoginResult.FailureReason.INVALID_CREDENTIALS);
        }

        List<RoleAssignment> grants = roleAssignmentRepository.findByUserId(user.getId());
        if (grants.isEmpty()) {
            log.warn("alert.grantless_user userId={} maskedEmail={}", user.getId(), maskedEmail);
            auditLog(user.getId(), maskedEmail, "GRANTLESS", 0);
            return LoginResult.failure(LoginResult.FailureReason.INVALID_CREDENTIALS);
        }

        List<String> roles = grants.stream().map(g -> g.getRoleName().name()).toList();
        String accessToken = tokenIssuer.issueAccessToken(user.getId(), canonical, roles);

        // Generate refresh handle: 256-bit SecureRandom, base64url-encoded
        byte[] handleBytes = new byte[32];
        secureRandom.nextBytes(handleBytes);
        String refreshHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(handleBytes);
        String refreshHash = sha256Hex(refreshHandle);

        Instant now = Instant.now();
        Instant familyExpiry = now.plus(authProperties.refreshToken().ttl());
        RefreshTokenFamily family = familyRepository.save(
                new RefreshTokenFamily(user.getId(), now, familyExpiry));
        tokenRepository.save(new RefreshToken(
                family.getId(), refreshHash, now,
                now.plus(authProperties.refreshToken().ttl())));

        // Publish audit event within this transaction (MANDATORY propagation)
        var payload = PiiRedactionUtility.toPayloadMap(
                new LoginAttemptedPayload(user.getId(), maskedEmail, "SUCCESS", roles.size()));
        eventPublisher.publish(DomainEvent.of(
                LoginAttemptedPayload.EVENT_TYPE,
                LoginAttemptedPayload.AGGREGATE_TYPE,
                user.getId(), now,
                MDC.get("traceId"), user.getId(), payload));

        attemptTracker.resetCounter(emailHash);
        auditLog(user.getId(), maskedEmail, "SUCCESS", roles.size());

        return LoginResult.success(user, roles, accessToken,
                refreshHandle, authProperties.jwt().accessTokenTtlSeconds());
    }

    private void recordFailureQuietly(String emailHash, String maskedEmail) {
        try {
            int newCount = attemptTracker.recordFailure(emailHash);
            log.debug("login.failure_recorded emailHash={}... count={}", emailHash.substring(0, 8), newCount);
        } catch (LoginAttemptTracker.LoginAttemptStoreException e) {
            log.warn("alert.login_failure_record_error maskedEmail={} error={}", maskedEmail, e.getMessage());
        }
    }

    private void auditLog(UUID actorId, String maskedEmail, String outcome, int roleCount) {
        log.info("audit.login actorId={} maskedEmail={} resource=AppUser operation=LOGIN outcome={} roleCount={}",
                actorId, maskedEmail, outcome, roleCount);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at < 0 ? "[invalid]" : email.substring(at);
    }

    /** Sealed result type — success carries all data needed to build the HTTP response. */
    public sealed interface LoginResult {
        record Success(
                AppUser user,
                List<String> roles,
                String accessToken,
                String refreshHandle,
                long expiresIn
        ) implements LoginResult {}

        record Failure(FailureReason reason) implements LoginResult {}

        enum FailureReason { INVALID_CREDENTIALS, STORE_UNAVAILABLE }

        static LoginResult success(AppUser user, List<String> roles, String accessToken,
                                   String refreshHandle, long expiresIn) {
            return new Success(user, roles, accessToken, refreshHandle, expiresIn);
        }

        static LoginResult failure(FailureReason reason) {
            return new Failure(reason);
        }
    }
}
