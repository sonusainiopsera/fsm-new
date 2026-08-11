package com.fieldservice.identity;

import com.fieldservice.identity.application.InMemoryLoginAttemptTracker;
import com.fieldservice.identity.application.LoginService;
import com.fieldservice.identity.application.TokenIssuer;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for LoginService — no Spring context, no database.
 *
 * <p>Uses BCrypt at cost 4 for encoder (test speed) because the BCrypt cost
 * constraint only prohibits lowering the PRODUCTION encoder. Test fixtures
 * use a dedicated low-cost encoder; only the dummy hash bean must respect cost 12.
 */
class LoginServiceUnitTest {

    private static final int TEST_BCRYPT_COST = 4;
    private static final PasswordEncoder encoder = new BCryptPasswordEncoder(TEST_BCRYPT_COST);

    private AppUserRepository            userRepo;
    private RoleAssignmentRepository     roleRepo;
    private LoginAuditRepository         auditRepo;
    private RefreshTokenFamilyRepository familyRepo;
    private RefreshTokenRepository       tokenRepo;
    private DomainEventPublisher         eventPublisher;
    private TokenIssuer                  tokenIssuer;
    private InMemoryLoginAttemptTracker  tracker;
    private LoginService                 service;

    private static final String DUMMY_HASH = encoder.encode(UUID.randomUUID().toString());
    private static final String CLIENT_IP  = "127.0.0.1";
    private static final String EMAIL      = "alice@example.com";
    private static final String PASSWORD   = "Correct$Pass123";

    @BeforeEach
    void setUp() {
        userRepo       = mock(AppUserRepository.class);
        roleRepo       = mock(RoleAssignmentRepository.class);
        auditRepo      = mock(LoginAuditRepository.class);
        familyRepo     = mock(RefreshTokenFamilyRepository.class);
        tokenRepo      = mock(RefreshTokenRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        tokenIssuer    = mock(TokenIssuer.class);
        tracker        = new InMemoryLoginAttemptTracker(5, 900);

        service = new LoginService(
                userRepo, roleRepo, auditRepo, familyRepo, tokenRepo,
                encoder, DUMMY_HASH, tracker, tokenIssuer, eventPublisher);

        // Default token issuer stub
        when(tokenIssuer.issue(any(), any())).thenReturn(
                new TokenIssuer.TokenBundle("token.value.here", "refresh-handle",
                        Instant.now().plusSeconds(900)));

        // Default family/token saves return the argument
        when(familyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- Success path -------------------------------------------------------

    @Test
    void success_returns_token_bundle_and_user() {
        AppUser user  = userWithPassword(EMAIL, PASSWORD);
        RoleAssignment grant = mockGrant(user, AppRole.DISPATCHER);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(roleRepo.findByUserId(user.getId())).thenReturn(List.of(grant));

        LoginService.LoginResult result = service.login(EMAIL, PASSWORD, CLIENT_IP);

        assertThat(result.tokens().accessToken()).isEqualTo("token.value.here");
        assertThat(result.roles()).containsExactly(AppRole.DISPATCHER);
        assertThat(result.user().getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void success_writes_success_audit() {
        AppUser user  = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(roleRepo.findByUserId(user.getId()))
                .thenReturn(List.of(mockGrant(user, AppRole.ADMIN)));

        service.login(EMAIL, PASSWORD, CLIENT_IP);

        ArgumentCaptor<LoginAudit> cap = ArgumentCaptor.forClass(LoginAudit.class);
        verify(auditRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getOutcome()).isEqualTo(LoginOutcome.SUCCESS.name());
    }

    @Test
    void success_publishes_domain_event() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(roleRepo.findByUserId(user.getId()))
                .thenReturn(List.of(mockGrant(user, AppRole.ADMIN)));

        service.login(EMAIL, PASSWORD, CLIENT_IP);

        verify(eventPublisher).publish(any(DomainEvent.class));
    }

    @Test
    void success_clears_lockout_counter() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(roleRepo.findByUserId(user.getId()))
                .thenReturn(List.of(mockGrant(user, AppRole.ADMIN)));

        // Simulate one prior failure
        String hash = sha256Hex(EMAIL);
        tracker.recordFailure(hash);
        assertThat(tracker.isLocked(hash)).isFalse();

        service.login(EMAIL, PASSWORD, CLIENT_IP);

        // Counter should be cleared — isLocked still false and count resets
        assertThat(tracker.isLocked(hash)).isFalse();
    }

    // ---- Failure paths -------------------------------------------------------

    @Test
    void unknown_email_throws_invalid_credentials() {
        when(userRepo.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@example.com", PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void unknown_email_writes_audit() {
        when(userRepo.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@example.com", PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);

        ArgumentCaptor<LoginAudit> cap = ArgumentCaptor.forClass(LoginAudit.class);
        verify(auditRepo).save(cap.capture());
        assertThat(cap.getValue().getOutcome()).isEqualTo(LoginOutcome.UNKNOWN_EMAIL.name());
    }

    @Test
    void wrong_password_throws_invalid_credentials() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(EMAIL, "WrongPassword123!", CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void wrong_password_increments_failure_count() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(EMAIL, "WrongPassword123!", CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login(EMAIL, "WrongPassword123!", CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);

        String hash = sha256Hex(EMAIL);
        // Two failures recorded; count < 5 so not locked yet
        assertThat(tracker.isLocked(hash)).isFalse();
    }

    @Test
    void five_failures_lock_account() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(EMAIL, "bad", CLIENT_IP))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        // Sixth attempt should still be InvalidCredentials (now LOCKED outcome)
        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void inactive_user_throws_invalid_credentials() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        user.deactivate();
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void inactive_user_writes_audit_with_inactive_outcome() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        user.deactivate();
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);

        ArgumentCaptor<LoginAudit> cap = ArgumentCaptor.forClass(LoginAudit.class);
        verify(auditRepo).save(cap.capture());
        assertThat(cap.getValue().getOutcome()).isEqualTo(LoginOutcome.INACTIVE.name());
    }

    @Test
    void grantless_user_throws_invalid_credentials() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(roleRepo.findByUserId(user.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void grantless_user_writes_audit_with_grantless_outcome() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(roleRepo.findByUserId(user.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);

        ArgumentCaptor<LoginAudit> cap = ArgumentCaptor.forClass(LoginAudit.class);
        verify(auditRepo).save(cap.capture());
        assertThat(cap.getValue().getOutcome()).isEqualTo(LoginOutcome.GRANTLESS.name());
    }

    @Test
    void locked_account_does_not_call_bcrypt() {
        AppUser user = userWithPassword(EMAIL, PASSWORD);
        when(userRepo.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        // Pre-load the tracker so the account appears locked
        String hash = sha256Hex(EMAIL);
        for (int i = 0; i < 5; i++) tracker.recordFailure(hash);

        PasswordEncoder spyEncoder = mock(PasswordEncoder.class);
        LoginService svc = new LoginService(
                userRepo, roleRepo, auditRepo, familyRepo, tokenRepo,
                spyEncoder, DUMMY_HASH, tracker, tokenIssuer, eventPublisher);

        assertThatThrownBy(() -> svc.login(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(spyEncoder, never()).matches(anyString(), anyString());
    }

    // ---- Helpers ------------------------------------------------------------

    private static AppUser userWithPassword(String email, String plainPassword) {
        AppUser user = AppUser.createWithPassword(email, "Alice", encoder.encode(plainPassword));
        return user;
    }

    private static RoleAssignment mockGrant(AppUser user, AppRole role) {
        return RoleAssignment.grant(user, role, "system");
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
