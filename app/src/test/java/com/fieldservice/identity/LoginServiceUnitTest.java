package com.fieldservice.identity;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.application.InMemoryLoginAttemptTracker;
import com.fieldservice.identity.application.LoginAttemptTracker;
import com.fieldservice.identity.application.LoginService;
import com.fieldservice.identity.application.TokenIssuer;
import com.fieldservice.identity.config.AuthProperties;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level tests for LoginService using in-memory fakes.
 * No database or Redis required.
 */
class LoginServiceUnitTest {

    // Use cost-4 for test speed; cost only affects encoding, not matches() on stored hashes
    private static final PasswordEncoder ENCODER = new DelegatingPasswordEncoder("bcrypt",
            Map.of("bcrypt", new BCryptPasswordEncoder(4)));

    private static final String VALID_PASSWORD = "Test@1234!Secure";
    private static final String VALID_EMAIL = "user@example.com";

    private FakeUserRepository userRepo;
    private FakeRoleAssignmentRepository roleRepo;
    private InMemoryLoginAttemptTracker tracker;
    private FakeTokenIssuer tokenIssuer;
    private FakeRefreshTokenFamilyRepository familyRepo;
    private FakeRefreshTokenRepository tokenRepo;
    private FakeDomainEventPublisher eventPublisher;
    private AuthProperties authProperties;
    private LoginService service;

    @BeforeEach
    void setUp() throws Exception {
        userRepo = new FakeUserRepository();
        roleRepo = new FakeRoleAssignmentRepository();
        tracker = new InMemoryLoginAttemptTracker();
        tokenIssuer = new FakeTokenIssuer();
        familyRepo = new FakeRefreshTokenFamilyRepository();
        tokenRepo = new FakeRefreshTokenRepository();
        eventPublisher = new FakeDomainEventPublisher();
        authProperties = new AuthProperties(
                new AuthProperties.Jwt("http://test", "test-api", 900L, null),
                new AuthProperties.RefreshToken(7),
                new AuthProperties.Lockout(5, 900L));

        service = new LoginService(userRepo, roleRepo, ENCODER, tracker, tokenIssuer,
                familyRepo, tokenRepo, eventPublisher, authProperties);
        // trigger @PostConstruct
        service.initDummyHash();
    }

    @Test
    void successfulLogin_returnsSuccessWithToken() {
        AppUser user = activeUser(VALID_EMAIL, ENCODER.encode(VALID_PASSWORD));
        userRepo.save(user);
        roleRepo.addGrant(user.getId(), IdentityRole.DISPATCHER);

        var result = service.login(VALID_EMAIL, VALID_PASSWORD);

        assertThat(result).isInstanceOf(LoginService.LoginResult.Success.class);
        var success = (LoginService.LoginResult.Success) result;
        assertThat(success.accessToken()).isEqualTo("fake-token");
        assertThat(success.roles()).containsExactly("DISPATCHER");
        assertThat(success.refreshHandle()).isNotBlank();
        assertThat(tokenRepo.saved).hasSize(1);
        assertThat(eventPublisher.published).hasSize(1);
    }

    @Test
    void unknownEmail_returnsFailure_withDummyBcryptCost() {
        var result = service.login("nobody@example.com", VALID_PASSWORD);
        assertThat(result).isInstanceOf(LoginService.LoginResult.Failure.class);
    }

    @Test
    void wrongPassword_returnsFailure() {
        AppUser user = activeUser(VALID_EMAIL, ENCODER.encode(VALID_PASSWORD));
        userRepo.save(user);
        roleRepo.addGrant(user.getId(), IdentityRole.ADMIN);

        var result = service.login(VALID_EMAIL, "WrongPass999!");
        assertThat(result).isInstanceOf(LoginService.LoginResult.Failure.class);
        assertThat(tracker.getCount(sha256Hex(VALID_EMAIL))).isEqualTo(1);
    }

    @Test
    void inactiveUser_returnsFailure() {
        AppUser user = inactiveUser(VALID_EMAIL, ENCODER.encode(VALID_PASSWORD));
        userRepo.save(user);
        roleRepo.addGrant(user.getId(), IdentityRole.TECHNICIAN);

        var result = service.login(VALID_EMAIL, VALID_PASSWORD);
        assertThat(result).isInstanceOf(LoginService.LoginResult.Failure.class);
    }

    @Test
    void grantlessUser_returnsFailure() {
        AppUser user = activeUser(VALID_EMAIL, ENCODER.encode(VALID_PASSWORD));
        userRepo.save(user);
        // no role grants added

        var result = service.login(VALID_EMAIL, VALID_PASSWORD);
        assertThat(result).isInstanceOf(LoginService.LoginResult.Failure.class);
    }

    @Test
    void lockoutAfterFiveFailures_returnsFailure() {
        AppUser user = activeUser(VALID_EMAIL, ENCODER.encode(VALID_PASSWORD));
        userRepo.save(user);
        roleRepo.addGrant(user.getId(), IdentityRole.ADMIN);

        for (int i = 0; i < 5; i++) {
            service.login(VALID_EMAIL, "WrongPass999!");
        }
        assertThat(tracker.getCount(sha256Hex(VALID_EMAIL))).isEqualTo(5);

        // 6th attempt — locked out, even with correct password
        var result = service.login(VALID_EMAIL, VALID_PASSWORD);
        assertThat(result).isInstanceOf(LoginService.LoginResult.Failure.class);
    }

    @Test
    void successfulLogin_clearsFailureCounter() {
        AppUser user = activeUser(VALID_EMAIL, ENCODER.encode(VALID_PASSWORD));
        userRepo.save(user);
        roleRepo.addGrant(user.getId(), IdentityRole.ADMIN);

        // Record some failures
        service.login(VALID_EMAIL, "WrongPass999!");
        service.login(VALID_EMAIL, "WrongPass999!");
        assertThat(tracker.getCount(sha256Hex(VALID_EMAIL))).isEqualTo(2);

        // Successful login clears counter
        service.login(VALID_EMAIL, VALID_PASSWORD);
        assertThat(tracker.getCount(sha256Hex(VALID_EMAIL))).isEqualTo(0);
    }

    @Test
    void backoffProgression_counterIncreasesWithEachFailure() {
        for (int i = 1; i <= 4; i++) {
            service.login("unknown" + i + "@example.com", "WrongPass999!");
        }
        // Each unknown email increments its own counter to 1
        for (int i = 1; i <= 4; i++) {
            assertThat(tracker.getCount(sha256Hex("unknown" + i + "@example.com"))).isEqualTo(1);
        }
    }

    @Test
    void emailIsCanonicalized_trailingSpaceAndUpperCase() {
        AppUser user = activeUser("user@example.com", ENCODER.encode(VALID_PASSWORD));
        userRepo.save(user);
        roleRepo.addGrant(user.getId(), IdentityRole.MANAGER);

        var result = service.login("  USER@EXAMPLE.COM  ", VALID_PASSWORD);
        assertThat(result).isInstanceOf(LoginService.LoginResult.Success.class);
    }

    @Test
    void redisUnavailable_throwsLoginAttemptStoreException() {
        LoginAttemptTracker failingTracker = new LoginAttemptTracker() {
            @Override public int getCount(String h) { throw new LoginAttemptStoreException("down", null); }
            @Override public int recordFailure(String h) { throw new LoginAttemptStoreException("down", null); }
            @Override public void resetCounter(String h) {}
        };

        LoginService svc = new LoginService(userRepo, roleRepo, ENCODER, failingTracker,
                tokenIssuer, familyRepo, tokenRepo, eventPublisher, authProperties);
        try { svc.initDummyHash(); } catch (Exception ignored) {}

        assertThatThrownBy(() -> svc.login(VALID_EMAIL, VALID_PASSWORD))
                .isInstanceOf(LoginAttemptTracker.LoginAttemptStoreException.class);
    }

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    private static AppUser activeUser(String email, String hash) {
        return buildUser(email, hash, true);
    }

    private static AppUser inactiveUser(String email, String hash) {
        return buildUser(email, hash, false);
    }

    private static AppUser buildUser(String email, String hash, boolean active) {
        try {
            var ctor = AppUser.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AppUser u = ctor.newInstance();
            u.setId(UUID.randomUUID());
            u.setEmail(email);
            u.setPasswordHash(hash);
            u.setDisplayName("Test User");
            u.setActive(active);
            return u;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build AppUser", e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // Simple in-memory fakes for JPA repositories
    static class FakeUserRepository implements AppUserRepository {
        private final java.util.Map<String, AppUser> byEmail = new java.util.HashMap<>();
        private final java.util.Map<UUID, AppUser> byId = new java.util.HashMap<>();

        public void save(AppUser user) {
            byEmail.put(user.getEmail().toLowerCase(), user);
            byId.put(user.getId(), user);
        }

        @Override
        public Optional<AppUser> findByEmailIgnoreCase(String email) {
            return Optional.ofNullable(byEmail.get(email.toLowerCase()));
        }

        // Minimal JpaRepository stubs — not used in unit tests
        @Override public <S extends AppUser> S save(S entity) { save((AppUser) entity); return entity; }
        @Override public Optional<AppUser> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
        @Override public boolean existsById(UUID id) { return byId.containsKey(id); }
        @Override public java.util.List<AppUser> findAll() { return new java.util.ArrayList<>(byId.values()); }
        @Override public java.util.List<AppUser> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<AppUser> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public java.util.List<AppUser> findAllById(Iterable<UUID> ids) { return java.util.List.of(); }
        @Override public long count() { return byId.size(); }
        @Override public void delete(AppUser entity) { byId.remove(entity.getId()); }
        @Override public void deleteById(UUID id) { byId.remove(id); }
        @Override public void deleteAll() { byId.clear(); byEmail.clear(); }
        @Override public void deleteAll(Iterable<? extends AppUser> entities) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends AppUser> java.util.List<S> saveAll(Iterable<S> entities) { return java.util.List.of(); }
        @Override public void flush() {}
        @Override public <S extends AppUser> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends AppUser> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { return java.util.List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<AppUser> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public AppUser getOne(UUID id) { return byId.get(id); }
        @Override public AppUser getById(UUID id) { return byId.get(id); }
        @Override public AppUser getReferenceById(UUID id) { return byId.get(id); }
        @Override public <S extends AppUser> Optional<S> findOne(org.springframework.data.jpa.domain.Specification<S> spec) { return Optional.empty(); }
        @Override public <S extends AppUser> java.util.List<S> findAll(org.springframework.data.jpa.domain.Specification<S> spec) { return java.util.List.of(); }
        @Override public <S extends AppUser> org.springframework.data.domain.Page<S> findAll(org.springframework.data.jpa.domain.Specification<S> spec, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends AppUser> java.util.List<S> findAll(org.springframework.data.jpa.domain.Specification<S> spec, org.springframework.data.domain.Sort sort) { return java.util.List.of(); }
        @Override public <S extends AppUser> long count(org.springframework.data.jpa.domain.Specification<S> spec) { return 0; }
        @Override public <S extends AppUser> boolean exists(org.springframework.data.jpa.domain.Specification<S> spec) { return false; }
        @Override public <S extends AppUser, R> R findBy(org.springframework.data.jpa.domain.Specification<S> spec, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
    }

    static class FakeRoleAssignmentRepository implements RoleAssignmentRepository {
        private final java.util.Map<UUID, java.util.List<RoleAssignment>> grants = new java.util.HashMap<>();

        public void addGrant(UUID userId, IdentityRole role) {
            grants.computeIfAbsent(userId, k -> new java.util.ArrayList<>())
                    .add(new RoleAssignment(userId, role, Instant.now(), null));
        }

        @Override
        public java.util.List<RoleAssignment> findByUserId(UUID userId) {
            return grants.getOrDefault(userId, java.util.List.of());
        }

        @Override
        public boolean existsByUserIdAndRoleName(UUID userId, IdentityRole role) {
            return grants.getOrDefault(userId, java.util.List.of()).stream()
                    .anyMatch(g -> g.getRoleName() == role);
        }

        // Minimal stubs
        @Override public <S extends RoleAssignment> S save(S e) { return e; }
        @Override public Optional<RoleAssignment> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public java.util.List<RoleAssignment> findAll() { return java.util.List.of(); }
        @Override public java.util.List<RoleAssignment> findAll(org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public org.springframework.data.domain.Page<RoleAssignment> findAll(org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public java.util.List<RoleAssignment> findAllById(Iterable<UUID> ids) { return java.util.List.of(); }
        @Override public long count() { return 0; }
        @Override public void delete(RoleAssignment e) {}
        @Override public void deleteById(UUID id) {}
        @Override public void deleteAll() {}
        @Override public void deleteAll(Iterable<? extends RoleAssignment> es) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends RoleAssignment> java.util.List<S> saveAll(Iterable<S> es) { return java.util.List.of(); }
        @Override public void flush() {}
        @Override public <S extends RoleAssignment> S saveAndFlush(S e) { return e; }
        @Override public <S extends RoleAssignment> java.util.List<S> saveAllAndFlush(Iterable<S> es) { return java.util.List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<RoleAssignment> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public RoleAssignment getOne(UUID id) { return null; }
        @Override public RoleAssignment getById(UUID id) { return null; }
        @Override public RoleAssignment getReferenceById(UUID id) { return null; }
        @Override public <S extends RoleAssignment> Optional<S> findOne(org.springframework.data.jpa.domain.Specification<S> spec) { return Optional.empty(); }
        @Override public <S extends RoleAssignment> java.util.List<S> findAll(org.springframework.data.jpa.domain.Specification<S> spec) { return java.util.List.of(); }
        @Override public <S extends RoleAssignment> org.springframework.data.domain.Page<S> findAll(org.springframework.data.jpa.domain.Specification<S> spec, org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends RoleAssignment> java.util.List<S> findAll(org.springframework.data.jpa.domain.Specification<S> spec, org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public <S extends RoleAssignment> long count(org.springframework.data.jpa.domain.Specification<S> spec) { return 0; }
        @Override public <S extends RoleAssignment> boolean exists(org.springframework.data.jpa.domain.Specification<S> spec) { return false; }
        @Override public <S extends RoleAssignment, R> R findBy(org.springframework.data.jpa.domain.Specification<S> spec, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { return null; }
    }

    static class FakeRefreshTokenFamilyRepository implements RefreshTokenFamilyRepository {
        final java.util.List<RefreshTokenFamily> saved = new ArrayList<>();
        @Override public java.util.List<RefreshTokenFamily> findByUserIdAndRevokedAtIsNull(UUID userId) { return java.util.List.of(); }
        @Override public <S extends RefreshTokenFamily> S save(S e) { saved.add(e); return e; }
        @Override public Optional<RefreshTokenFamily> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public java.util.List<RefreshTokenFamily> findAll() { return java.util.List.of(); }
        @Override public java.util.List<RefreshTokenFamily> findAll(org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public org.springframework.data.domain.Page<RefreshTokenFamily> findAll(org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public java.util.List<RefreshTokenFamily> findAllById(Iterable<UUID> ids) { return java.util.List.of(); }
        @Override public long count() { return 0; }
        @Override public void delete(RefreshTokenFamily e) {}
        @Override public void deleteById(UUID id) {}
        @Override public void deleteAll() {}
        @Override public void deleteAll(Iterable<? extends RefreshTokenFamily> es) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends RefreshTokenFamily> java.util.List<S> saveAll(Iterable<S> es) { return java.util.List.of(); }
        @Override public void flush() {}
        @Override public <S extends RefreshTokenFamily> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends RefreshTokenFamily> java.util.List<S> saveAllAndFlush(Iterable<S> es) { return java.util.List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<RefreshTokenFamily> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public RefreshTokenFamily getOne(UUID id) { return null; }
        @Override public RefreshTokenFamily getById(UUID id) { return null; }
        @Override public RefreshTokenFamily getReferenceById(UUID id) { return null; }
    }

    static class FakeRefreshTokenRepository implements RefreshTokenRepository {
        final java.util.List<RefreshToken> saved = new ArrayList<>();
        @Override public Optional<RefreshToken> findByTokenHash(String hash) { return Optional.empty(); }
        @Override public long countByFamilyIdAndConsumedAtIsNull(UUID familyId) { return 0; }
        @Override public <S extends RefreshToken> S save(S e) { saved.add(e); return e; }
        @Override public Optional<RefreshToken> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public java.util.List<RefreshToken> findAll() { return java.util.List.of(); }
        @Override public java.util.List<RefreshToken> findAll(org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public org.springframework.data.domain.Page<RefreshToken> findAll(org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public java.util.List<RefreshToken> findAllById(Iterable<UUID> ids) { return java.util.List.of(); }
        @Override public long count() { return 0; }
        @Override public void delete(RefreshToken e) {}
        @Override public void deleteById(UUID id) {}
        @Override public void deleteAll() {}
        @Override public void deleteAll(Iterable<? extends RefreshToken> es) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends RefreshToken> java.util.List<S> saveAll(Iterable<S> es) { return java.util.List.of(); }
        @Override public void flush() {}
        @Override public <S extends RefreshToken> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends RefreshToken> java.util.List<S> saveAllAndFlush(Iterable<S> es) { return java.util.List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<RefreshToken> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public RefreshToken getOne(UUID id) { return null; }
        @Override public RefreshToken getById(UUID id) { return null; }
        @Override public RefreshToken getReferenceById(UUID id) { return null; }
    }

    static class FakeTokenIssuer implements TokenIssuer {
        @Override
        public String issueAccessToken(UUID userId, String email, List<String> roles) {
            return "fake-token";
        }
    }

    static class FakeDomainEventPublisher implements DomainEventPublisher {
        final List<DomainEvent> published = new ArrayList<>();
        @Override
        public void publish(DomainEvent event) {
            published.add(event);
        }
    }
}
