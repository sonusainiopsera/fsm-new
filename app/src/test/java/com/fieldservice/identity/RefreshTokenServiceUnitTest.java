package com.fieldservice.identity;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.application.RefreshTokenService;
import com.fieldservice.identity.application.SecurityEventPublisher;
import com.fieldservice.identity.application.TokenIssuer;
import com.fieldservice.identity.config.AuthProperties;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RefreshToken;
import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.identity.domain.RefreshTokenFamilyRepository;
import com.fieldservice.identity.domain.RefreshTokenRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.FluentQuery;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RefreshTokenService using in-memory fake repositories.
 *
 * Tests all rotation branches:
 *   - Valid rotation → Success with new handle
 *   - Consumed-handle reuse (first detection) → REUSE_DETECTED, family revoked, SIEM published
 *   - Reuse on already-revoked family → REVOKED_FAMILY, only counter incremented
 *   - Expired family → EXPIRED_FAMILY, family marked revoked with EXPIRED reason
 *   - Unknown hash → UNKNOWN_HANDLE
 *   - Inactive owner → INACTIVE_USER
 *   - Concurrently revoked family after consume → REVOKED_FAMILY
 *   - New token carries same absolute_expires_at as family (rotation never extends)
 */
class RefreshTokenServiceUnitTest {

    private FakeRefreshTokenRepository tokenRepo;
    private FakeRefreshTokenFamilyRepository familyRepo;
    private FakeAppUserRepository userRepo;
    private FakeRoleAssignmentRepository roleRepo;
    private FakeTokenIssuer tokenIssuer;
    private FakeSecurityEventPublisher securityEventPublisher;
    private AuthProperties authProperties;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        tokenRepo = new FakeRefreshTokenRepository();
        familyRepo = new FakeRefreshTokenFamilyRepository();
        userRepo = new FakeAppUserRepository();
        roleRepo = new FakeRoleAssignmentRepository();
        tokenIssuer = new FakeTokenIssuer();
        securityEventPublisher = new FakeSecurityEventPublisher();
        authProperties = new AuthProperties(
                new AuthProperties.Jwt("http://test", "test-api", 900L, null),
                new AuthProperties.RefreshToken(7),
                new AuthProperties.Lockout(5, 900L));
        service = new RefreshTokenService(tokenRepo, familyRepo, userRepo, roleRepo,
                tokenIssuer, securityEventPublisher, authProperties);
    }

    // -------------------------------------------------------------------------
    // 1. Valid rotation
    // -------------------------------------------------------------------------

    @Test
    void validRotation_returnsSuccessWithNewHandle() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().plusSeconds(604800);

        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry, null, null);
        familyRepo.add(family);

        String rawHandle = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String hash = sha256Hex(rawHandle);
        RefreshToken token = makeToken(UUID.randomUUID(), familyId, hash, false);
        tokenRepo.add(token, hash);

        AppUser user = makeActiveUser(userId, "user@example.com");
        userRepo.add(user);
        roleRepo.addGrant(userId, IdentityRole.DISPATCHER);

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-1", "127.0.0.1", "Test/1.0");

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Success.class);
        RefreshTokenService.RotationResult.Success success = (RefreshTokenService.RotationResult.Success) result;
        assertThat(success.accessToken()).isEqualTo("fake-token");
        assertThat(success.refreshHandle()).isNotBlank();
        assertThat(success.refreshHandle()).isNotEqualTo(rawHandle);
        assertThat(success.expiresIn()).isEqualTo(900L);
        // One new token saved by rotation
        assertThat(tokenRepo.savedTokens).hasSize(1);
        // No SIEM event published for normal rotation
        assertThat(securityEventPublisher.reuseDetectedCount).isZero();
        assertThat(securityEventPublisher.incrementedCount).isZero();
    }

    @Test
    void validRotation_newTokenHasSameFamilyAbsoluteExpiry() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().plusSeconds(604800);

        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry, null, null);
        familyRepo.add(family);

        String rawHandle = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        String hash = sha256Hex(rawHandle);
        tokenRepo.add(makeToken(UUID.randomUUID(), familyId, hash, false), hash);
        userRepo.add(makeActiveUser(userId, "user@example.com"));
        roleRepo.addGrant(userId, IdentityRole.TECHNICIAN);

        service.rotate(rawHandle, "trace-2", "127.0.0.1", null);

        assertThat(tokenRepo.savedTokens).hasSize(1);
        RefreshToken saved = tokenRepo.savedTokens.get(0);
        // New token's expiresAt must equal the family's absoluteExpiresAt (never extended)
        assertThat(saved.getExpiresAt()).isEqualTo(familyExpiry);
        assertThat(saved.getFamilyId()).isEqualTo(familyId);
    }

    // -------------------------------------------------------------------------
    // 2. Consumed-handle reuse — first detection
    // -------------------------------------------------------------------------

    @Test
    void consumedHandleReuse_firstDetection_revokesFamily_publishesSiemEvent() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().plusSeconds(604800);

        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry, null, null);
        familyRepo.add(family);

        String rawHandle = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        String hash = sha256Hex(rawHandle);
        // Token is already consumed — consumeByTokenHash returns 0
        RefreshToken alreadyConsumed = makeToken(UUID.randomUUID(), familyId, hash, true);
        tokenRepo.add(alreadyConsumed, hash);

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-3", "1.2.3.4", "UA");

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Failure.class);
        RefreshTokenService.RotationResult.Failure failure = (RefreshTokenService.RotationResult.Failure) result;
        assertThat(failure.reason()).isEqualTo(RefreshTokenService.RotationResult.FailureReason.REUSE_DETECTED);

        // Family must be revoked
        assertThat(family.isRevoked()).isTrue();
        assertThat(family.getRevokedReason()).isEqualTo("REPLAY_ATTACK");

        // SIEM event published exactly once for first detection
        assertThat(securityEventPublisher.reuseDetectedCount).isEqualTo(1);
        assertThat(securityEventPublisher.lastReuseUserId).isEqualTo(userId);
        assertThat(securityEventPublisher.lastReuseFamilyId).isEqualTo(familyId);

        // Counter-only path NOT used (that's for subsequent reuse on already-revoked)
        assertThat(securityEventPublisher.incrementedCount).isZero();
    }

    // -------------------------------------------------------------------------
    // 3. Reuse on already-revoked family — SIEM deduplication
    // -------------------------------------------------------------------------

    @Test
    void reuseOnAlreadyRevokedFamily_onlyIncrementsCounter_noNewSiemEvent() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().plusSeconds(604800);

        // Family already revoked
        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry,
                Instant.now().minusSeconds(60), "REPLAY_ATTACK");
        familyRepo.add(family);

        String rawHandle = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";
        String hash = sha256Hex(rawHandle);
        RefreshToken alreadyConsumed = makeToken(UUID.randomUUID(), familyId, hash, true);
        tokenRepo.add(alreadyConsumed, hash);

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-4", "1.2.3.4", "UA");

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Failure.class);
        assertThat(((RefreshTokenService.RotationResult.Failure) result).reason())
                .isEqualTo(RefreshTokenService.RotationResult.FailureReason.REVOKED_FAMILY);

        // Only counter incremented — no new critical SIEM event
        assertThat(securityEventPublisher.incrementedCount).isEqualTo(1);
        assertThat(securityEventPublisher.reuseDetectedCount).isZero();
    }

    // -------------------------------------------------------------------------
    // 4. Expired family
    // -------------------------------------------------------------------------

    @Test
    void expiredFamily_returnsExpiredFamily_andRevokesWithExpiredReason() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().minusSeconds(86400); // yesterday

        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry, null, null);
        familyRepo.add(family);

        String rawHandle = "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE";
        String hash = sha256Hex(rawHandle);
        tokenRepo.add(makeToken(UUID.randomUUID(), familyId, hash, false), hash);

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-5", null, null);

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Failure.class);
        assertThat(((RefreshTokenService.RotationResult.Failure) result).reason())
                .isEqualTo(RefreshTokenService.RotationResult.FailureReason.EXPIRED_FAMILY);

        assertThat(family.isRevoked()).isTrue();
        assertThat(family.getRevokedReason()).isEqualTo("EXPIRED");
    }

    // -------------------------------------------------------------------------
    // 5. Unknown hash
    // -------------------------------------------------------------------------

    @Test
    void unknownHash_returnsUnknownHandle() {
        String rawHandle = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF";

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-6", null, null);

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Failure.class);
        assertThat(((RefreshTokenService.RotationResult.Failure) result).reason())
                .isEqualTo(RefreshTokenService.RotationResult.FailureReason.UNKNOWN_HANDLE);
        assertThat(securityEventPublisher.reuseDetectedCount).isZero();
        assertThat(securityEventPublisher.incrementedCount).isZero();
    }

    // -------------------------------------------------------------------------
    // 6. Inactive user
    // -------------------------------------------------------------------------

    @Test
    void inactiveOwner_returnsInactiveUser() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().plusSeconds(604800);

        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry, null, null);
        familyRepo.add(family);

        String rawHandle = "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG";
        String hash = sha256Hex(rawHandle);
        tokenRepo.add(makeToken(UUID.randomUUID(), familyId, hash, false), hash);

        // User is inactive
        AppUser user = makeInactiveUser(userId, "inactive@example.com");
        userRepo.add(user);
        roleRepo.addGrant(userId, IdentityRole.TECHNICIAN);

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-7", null, null);

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Failure.class);
        assertThat(((RefreshTokenService.RotationResult.Failure) result).reason())
                .isEqualTo(RefreshTokenService.RotationResult.FailureReason.INACTIVE_USER);
    }

    // -------------------------------------------------------------------------
    // 7. Concurrently revoked family (consumed but family revoked before success path)
    // -------------------------------------------------------------------------

    @Test
    void concurrentRevocation_afterConsume_returnsRevokedFamily() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().plusSeconds(604800);

        // Family will be revoked by the time handleConsumeSuccess reads it
        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry,
                Instant.now().minusSeconds(1), "REPLAY_ATTACK");
        familyRepo.add(family);

        String rawHandle = "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH";
        String hash = sha256Hex(rawHandle);
        // Token is unconsumed — consume will succeed (returns 1)
        tokenRepo.add(makeToken(UUID.randomUUID(), familyId, hash, false), hash);

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-8", null, null);

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Failure.class);
        assertThat(((RefreshTokenService.RotationResult.Failure) result).reason())
                .isEqualTo(RefreshTokenService.RotationResult.FailureReason.REVOKED_FAMILY);
    }

    // -------------------------------------------------------------------------
    // 8. Missing user record (user deleted after family created)
    // -------------------------------------------------------------------------

    @Test
    void missingUserRecord_returnsInactiveUser() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = Instant.now().plusSeconds(604800);

        RefreshTokenFamily family = makeFamily(familyId, userId, familyExpiry, null, null);
        familyRepo.add(family);

        String rawHandle = "IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII";
        String hash = sha256Hex(rawHandle);
        tokenRepo.add(makeToken(UUID.randomUUID(), familyId, hash, false), hash);
        // No user in repo

        RefreshTokenService.RotationResult result = service.rotate(rawHandle, "trace-9", null, null);

        assertThat(result).isInstanceOf(RefreshTokenService.RotationResult.Failure.class);
        assertThat(((RefreshTokenService.RotationResult.Failure) result).reason())
                .isEqualTo(RefreshTokenService.RotationResult.FailureReason.INACTIVE_USER);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RefreshTokenFamily makeFamily(UUID id, UUID userId, Instant absoluteExpiry,
                                                  Instant revokedAt, String revokedReason) {
        RefreshTokenFamily f = new RefreshTokenFamily(userId, Instant.now(), absoluteExpiry);
        setFamilyId(f, id);
        if (revokedAt != null) {
            f.revoke(revokedAt, revokedReason);
        }
        return f;
    }

    private static void setFamilyId(RefreshTokenFamily f, UUID id) {
        try {
            var field = RefreshTokenFamily.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(f, id);
        } catch (Exception e) {
            throw new RuntimeException("Could not set family id", e);
        }
    }

    private static void setTokenId(RefreshToken t, UUID id) {
        try {
            var field = RefreshToken.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(t, id);
        } catch (Exception e) {
            throw new RuntimeException("Could not set token id", e);
        }
    }

    private static RefreshToken makeToken(UUID id, UUID familyId, String hash, boolean consumed) {
        Instant now = Instant.now();
        RefreshToken t = new RefreshToken(familyId, hash, now, now.plusSeconds(604800));
        setTokenId(t, id);
        if (consumed) {
            t.consume(now.minusSeconds(3600));
        }
        return t;
    }

    private static AppUser makeActiveUser(UUID id, String email) {
        return buildUser(id, email, true);
    }

    private static AppUser makeInactiveUser(UUID id, String email) {
        return buildUser(id, email, false);
    }

    private static AppUser buildUser(UUID id, String email, boolean active) {
        try {
            var ctor = AppUser.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AppUser u = ctor.newInstance();
            u.setId(id);
            u.setEmail(email);
            u.setDisplayName("Test User");
            u.setActive(active);
            return u;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build AppUser", e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Fake repositories
    // -------------------------------------------------------------------------

    static class FakeRefreshTokenRepository implements RefreshTokenRepository {
        private final Map<String, RefreshToken> byHash = new HashMap<>();
        final List<RefreshToken> savedTokens = new ArrayList<>();

        void add(RefreshToken token, String hash) {
            byHash.put(hash, token);
        }

        @Override
        public Optional<RefreshToken> findByTokenHash(String hash) {
            return Optional.ofNullable(byHash.get(hash));
        }

        @Override
        public int consumeByTokenHash(String hash) {
            RefreshToken t = byHash.get(hash);
            if (t == null || t.isConsumed()) return 0;
            t.consume(Instant.now());
            return 1;
        }

        @Override
        public long countByFamilyIdAndConsumedAtIsNull(UUID familyId) { return 0; }

        @Override public <S extends RefreshToken> S save(S e) { savedTokens.add(e); return e; }
        @Override public Optional<RefreshToken> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public List<RefreshToken> findAll() { return List.of(); }
        @Override public List<RefreshToken> findAll(Sort s) { return List.of(); }
        @Override public Page<RefreshToken> findAll(Pageable p) { return Page.empty(); }
        @Override public List<RefreshToken> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void delete(RefreshToken e) {}
        @Override public void deleteById(UUID id) {}
        @Override public void deleteAll() {}
        @Override public void deleteAll(Iterable<? extends RefreshToken> es) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends RefreshToken> List<S> saveAll(Iterable<S> es) { return List.of(); }
        @Override public void flush() {}
        @Override public <S extends RefreshToken> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends RefreshToken> List<S> saveAllAndFlush(Iterable<S> es) { return List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<RefreshToken> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public RefreshToken getOne(UUID id) { return null; }
        @Override public RefreshToken getById(UUID id) { return null; }
        @Override public RefreshToken getReferenceById(UUID id) { return null; }
        @Override public <S extends RefreshToken> Optional<S> findOne(Specification<S> spec) { return Optional.empty(); }
        @Override public <S extends RefreshToken> List<S> findAll(Specification<S> spec) { return List.of(); }
        @Override public <S extends RefreshToken> Page<S> findAll(Specification<S> spec, Pageable pageable) { return Page.empty(); }
        @Override public <S extends RefreshToken> List<S> findAll(Specification<S> spec, Sort sort) { return List.of(); }
        @Override public <S extends RefreshToken> long count(Specification<S> spec) { return 0; }
        @Override public <S extends RefreshToken> boolean exists(Specification<S> spec) { return false; }
        @Override public <S extends RefreshToken, R> R findBy(Specification<S> spec, Function<FluentQuery.FetchableFluentQuery<S>, R> f) { return null; }
    }

    static class FakeRefreshTokenFamilyRepository implements RefreshTokenFamilyRepository {
        private final Map<UUID, RefreshTokenFamily> byId = new HashMap<>();

        void add(RefreshTokenFamily family) {
            byId.put(family.getId(), family);
        }

        @Override
        public List<RefreshTokenFamily> findByUserIdAndRevokedAtIsNull(UUID userId) {
            return byId.values().stream()
                    .filter(f -> f.getUserId().equals(userId) && !f.isRevoked())
                    .toList();
        }

        @Override public <S extends RefreshTokenFamily> S save(S e) { byId.put(e.getId(), e); return e; }
        @Override public Optional<RefreshTokenFamily> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
        @Override public boolean existsById(UUID id) { return byId.containsKey(id); }
        @Override public List<RefreshTokenFamily> findAll() { return new ArrayList<>(byId.values()); }
        @Override public List<RefreshTokenFamily> findAll(Sort s) { return findAll(); }
        @Override public Page<RefreshTokenFamily> findAll(Pageable p) { return Page.empty(); }
        @Override public List<RefreshTokenFamily> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return byId.size(); }
        @Override public void delete(RefreshTokenFamily e) { byId.remove(e.getId()); }
        @Override public void deleteById(UUID id) { byId.remove(id); }
        @Override public void deleteAll() { byId.clear(); }
        @Override public void deleteAll(Iterable<? extends RefreshTokenFamily> es) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends RefreshTokenFamily> List<S> saveAll(Iterable<S> es) { return List.of(); }
        @Override public void flush() {}
        @Override public <S extends RefreshTokenFamily> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends RefreshTokenFamily> List<S> saveAllAndFlush(Iterable<S> es) { return List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<RefreshTokenFamily> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public RefreshTokenFamily getOne(UUID id) { return byId.get(id); }
        @Override public RefreshTokenFamily getById(UUID id) { return byId.get(id); }
        @Override public RefreshTokenFamily getReferenceById(UUID id) { return byId.get(id); }
    }

    static class FakeAppUserRepository implements AppUserRepository {
        private final Map<UUID, AppUser> byId = new HashMap<>();
        private final Map<String, AppUser> byEmail = new HashMap<>();

        void add(AppUser user) {
            byId.put(user.getId(), user);
            byEmail.put(user.getEmail().toLowerCase(), user);
        }

        @Override public Optional<AppUser> findByEmailIgnoreCase(String email) {
            return Optional.ofNullable(byEmail.get(email.toLowerCase()));
        }
        @Override public <S extends AppUser> S save(S e) { add(e); return e; }
        @Override public Optional<AppUser> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
        @Override public boolean existsById(UUID id) { return byId.containsKey(id); }
        @Override public List<AppUser> findAll() { return new ArrayList<>(byId.values()); }
        @Override public List<AppUser> findAll(Sort s) { return findAll(); }
        @Override public Page<AppUser> findAll(Pageable p) { return Page.empty(); }
        @Override public List<AppUser> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return byId.size(); }
        @Override public void delete(AppUser e) { byId.remove(e.getId()); }
        @Override public void deleteById(UUID id) { byId.remove(id); }
        @Override public void deleteAll() { byId.clear(); byEmail.clear(); }
        @Override public void deleteAll(Iterable<? extends AppUser> es) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends AppUser> List<S> saveAll(Iterable<S> es) { return List.of(); }
        @Override public void flush() {}
        @Override public <S extends AppUser> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends AppUser> List<S> saveAllAndFlush(Iterable<S> es) { return List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<AppUser> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public AppUser getOne(UUID id) { return byId.get(id); }
        @Override public AppUser getById(UUID id) { return byId.get(id); }
        @Override public AppUser getReferenceById(UUID id) { return byId.get(id); }
        @Override public <S extends AppUser> Optional<S> findOne(Specification<S> spec) { return Optional.empty(); }
        @Override public <S extends AppUser> List<S> findAll(Specification<S> spec) { return List.of(); }
        @Override public <S extends AppUser> Page<S> findAll(Specification<S> spec, Pageable p) { return Page.empty(); }
        @Override public <S extends AppUser> List<S> findAll(Specification<S> spec, Sort sort) { return List.of(); }
        @Override public <S extends AppUser> long count(Specification<S> spec) { return 0; }
        @Override public <S extends AppUser> boolean exists(Specification<S> spec) { return false; }
        @Override public <S extends AppUser, R> R findBy(Specification<S> spec, Function<FluentQuery.FetchableFluentQuery<S>, R> f) { return null; }
    }

    static class FakeRoleAssignmentRepository implements RoleAssignmentRepository {
        private final Map<UUID, List<RoleAssignment>> grants = new HashMap<>();

        void addGrant(UUID userId, IdentityRole role) {
            grants.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(new RoleAssignment(userId, role, Instant.now(), null));
        }

        @Override public List<RoleAssignment> findByUserId(UUID userId) {
            return grants.getOrDefault(userId, List.of());
        }
        @Override public boolean existsByUserIdAndRoleName(UUID userId, IdentityRole role) {
            return grants.getOrDefault(userId, List.of()).stream()
                    .anyMatch(g -> g.getRoleName() == role);
        }
        @Override public <S extends RoleAssignment> S save(S e) { return e; }
        @Override public Optional<RoleAssignment> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public List<RoleAssignment> findAll() { return List.of(); }
        @Override public List<RoleAssignment> findAll(Sort s) { return List.of(); }
        @Override public Page<RoleAssignment> findAll(Pageable p) { return Page.empty(); }
        @Override public List<RoleAssignment> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void delete(RoleAssignment e) {}
        @Override public void deleteById(UUID id) {}
        @Override public void deleteAll() {}
        @Override public void deleteAll(Iterable<? extends RoleAssignment> es) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public <S extends RoleAssignment> List<S> saveAll(Iterable<S> es) { return List.of(); }
        @Override public void flush() {}
        @Override public <S extends RoleAssignment> S saveAndFlush(S e) { return e; }
        @Override public <S extends RoleAssignment> List<S> saveAllAndFlush(Iterable<S> es) { return List.of(); }
        @Override public void deleteAllInBatch() {}
        @Override public void deleteAllInBatch(Iterable<RoleAssignment> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public RoleAssignment getOne(UUID id) { return null; }
        @Override public RoleAssignment getById(UUID id) { return null; }
        @Override public RoleAssignment getReferenceById(UUID id) { return null; }
        @Override public <S extends RoleAssignment> Optional<S> findOne(Specification<S> spec) { return Optional.empty(); }
        @Override public <S extends RoleAssignment> List<S> findAll(Specification<S> spec) { return List.of(); }
        @Override public <S extends RoleAssignment> Page<S> findAll(Specification<S> spec, Pageable p) { return Page.empty(); }
        @Override public <S extends RoleAssignment> List<S> findAll(Specification<S> spec, Sort s) { return List.of(); }
        @Override public <S extends RoleAssignment> long count(Specification<S> spec) { return 0; }
        @Override public <S extends RoleAssignment> boolean exists(Specification<S> spec) { return false; }
        @Override public <S extends RoleAssignment, R> R findBy(Specification<S> spec, Function<FluentQuery.FetchableFluentQuery<S>, R> f) { return null; }
    }

    static class FakeTokenIssuer implements TokenIssuer {
        @Override
        public String issueAccessToken(UUID userId, String email, List<String> roles) {
            return "fake-token";
        }
    }

    static class FakeSecurityEventPublisher extends SecurityEventPublisher {
        int reuseDetectedCount = 0;
        int incrementedCount = 0;
        UUID lastReuseUserId;
        UUID lastReuseFamilyId;

        FakeSecurityEventPublisher() {
            super(null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }

        @Override
        public void publishReuseDetected(UUID userId, UUID familyId, String traceId,
                                          String clientIp, String userAgent) {
            reuseDetectedCount++;
            lastReuseUserId = userId;
            lastReuseFamilyId = familyId;
        }

        @Override
        public void incrementReuseCounter(UUID familyId, String traceId) {
            incrementedCount++;
        }
    }
}
