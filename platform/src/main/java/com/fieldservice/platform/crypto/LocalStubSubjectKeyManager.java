package com.fieldservice.platform.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Local (non-KMS) implementation of {@link SubjectKeyManager} for development and testing.
 *
 * <h2>Key wrapping</h2>
 * Each per-subject data key (AES-256) is wrapped using the global {@code legacyDataKey}
 * (AES-256-GCM) before storage in {@code subject_data_key.wrapped_key}.  The plaintext
 * data key exists only in memory during generate and resolve operations.
 *
 * <p>In production this adapter is replaced by an AWS KMS or Azure Key Vault implementation
 * that uses the managed key service's envelope encryption API.
 *
 * <h2>Storage</h2>
 * Uses {@link JdbcTemplate} directly to avoid a circular dependency between the JPA
 * converter bean ({@link EnvelopeEncryptedStringConverter}) and Spring Data JPA
 * repository initialisation.
 */
public class LocalStubSubjectKeyManager implements SubjectKeyManager {

    private static final Logger log = LoggerFactory.getLogger(LocalStubSubjectKeyManager.class);

    private static final String WRAP_ALGORITHM = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN     = 12;
    private static final int    GCM_TAG_BITS   = 128;
    private static final int    DATA_KEY_BITS  = 256;

    private final JdbcTemplate   jdbc;
    private final SecretKey      masterKey;
    private final SubjectKeyCache cache;

    public LocalStubSubjectKeyManager(JdbcTemplate jdbc, SecretKey masterKey, SubjectKeyCache cache) {
        this.jdbc      = jdbc;
        this.masterKey = masterKey;
        this.cache     = cache;
    }

    @Override
    public SubjectKeySpec generate(SubjectRef subject) {
        Optional<SubjectKeySpec> active = findActive(subject);
        if (active.isPresent()) {
            return active.get();
        }
        return createNewVersion(subject, 1);
    }

    @Override
    public SubjectKeySpec resolveActive(SubjectRef subject) {
        Optional<SubjectKeySpec> active = findActive(subject);
        if (active.isPresent()) {
            return active.get();
        }
        return createNewVersion(subject, 1);
    }

    @Override
    public SubjectKeySpec resolve(SubjectRef subject, int keyVersion) {
        Optional<SubjectKeySpec> cached = cache.get(subject, keyVersion);
        if (cached.isPresent()) {
            return cached.get();
        }

        SubjectKeyRow row = queryByVersion(subject, keyVersion);

        if (SubjectKeyState.DESTROYED.name().equals(row.state())) {
            throw new SubjectKeyDestroyedException(subject, keyVersion);
        }

        SecretKey plainKey = unwrapKey(row.wrappedKey());
        SubjectKeySpec spec = new SubjectKeySpec(plainKey, keyVersion);
        cache.put(subject, spec);
        return spec;
    }

    @Override
    public void rotate(SubjectRef subject) {
        cache.evict(subject);

        Integer currentVersion = findActiveVersion(subject);
        int nextVersion = (currentVersion == null ? 0 : currentVersion) + 1;

        if (currentVersion != null) {
            jdbc.update(
                    "UPDATE subject_data_key SET state = ?, rotated_at = ? " +
                    "WHERE subject_type = ? AND subject_id = ? AND state = ?",
                    SubjectKeyState.ROTATED.name(),
                    Instant.now(),
                    subject.subjectType(),
                    subject.subjectId(),
                    SubjectKeyState.ACTIVE.name()
            );
        }
        createNewVersion(subject, nextVersion);
        log.info("Rotated key for subject {}/{} to version {}",
                subject.subjectType(), subject.subjectId(), nextVersion);
    }

    @Override
    public void destroy(SubjectRef subject) {
        cache.evict(subject);

        int updated = jdbc.update(
                "UPDATE subject_data_key " +
                "SET state = ?, wrapped_key = ?, destroyed_at = ? " +
                "WHERE subject_type = ? AND subject_id = ? AND state != ?",
                SubjectKeyState.DESTROYED.name(),
                new byte[0],
                Instant.now(),
                subject.subjectType(),
                subject.subjectId(),
                SubjectKeyState.DESTROYED.name()
        );
        log.info("Destroyed {} key version(s) for subject {}/{}",
                updated, subject.subjectType(), subject.subjectId());
    }

    // ---- private helpers ---------------------------------------------------

    private Optional<SubjectKeySpec> findActive(SubjectRef subject) {
        Integer version = findActiveVersion(subject);
        if (version == null) {
            return Optional.empty();
        }
        Optional<SubjectKeySpec> cached = cache.get(subject, version);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            SubjectKeySpec spec = resolve(subject, version);
            return Optional.of(spec);
        } catch (SubjectKeyDestroyedException e) {
            return Optional.empty();
        }
    }

    private Integer findActiveVersion(SubjectRef subject) {
        return jdbc.query(
                "SELECT key_version FROM subject_data_key " +
                "WHERE subject_type = ? AND subject_id = ? AND state = ? " +
                "ORDER BY key_version DESC LIMIT 1",
                rs -> rs.next() ? rs.getInt("key_version") : null,
                subject.subjectType(),
                subject.subjectId(),
                SubjectKeyState.ACTIVE.name()
        );
    }

    private SubjectKeyRow queryByVersion(SubjectRef subject, int keyVersion) {
        return jdbc.queryForObject(
                "SELECT key_version, wrapped_key, state FROM subject_data_key " +
                "WHERE subject_type = ? AND subject_id = ? AND key_version = ?",
                (rs, rowNum) -> new SubjectKeyRow(
                        rs.getInt("key_version"),
                        rs.getBytes("wrapped_key"),
                        rs.getString("state")
                ),
                subject.subjectType(),
                subject.subjectId(),
                keyVersion
        );
    }

    private SubjectKeySpec createNewVersion(SubjectRef subject, int version) {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(DATA_KEY_BITS, new SecureRandom());
            SecretKey plainKey  = kg.generateKey();
            byte[]    wrapped   = wrapKey(plainKey);

            jdbc.update(
                    "INSERT INTO subject_data_key " +
                    "(id, subject_type, subject_id, key_version, wrapped_key, state, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    subject.subjectType(),
                    subject.subjectId(),
                    version,
                    wrapped,
                    SubjectKeyState.ACTIVE.name(),
                    Instant.now()
            );

            SubjectKeySpec spec = new SubjectKeySpec(plainKey, version);
            cache.put(subject, spec);
            return spec;

        } catch (Exception e) {
            throw new KeyManagementUnavailableException("Failed to generate data key", e);
        }
    }

    /** Wraps {@code dataKey} using the master key via AES-256-GCM. */
    private byte[] wrapKey(SecretKey dataKey) throws Exception {
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(dataKey.getEncoded());

        return ByteBuffer.allocate(iv.length + encrypted.length)
                .put(iv).put(encrypted).array();
    }

    /** Unwraps a wrapped data key using the master key. */
    private SecretKey unwrapKey(byte[] wrapped) {
        if (wrapped == null || wrapped.length == 0) {
            throw new SubjectKeyDestroyedException(
                    SubjectRef.of("unknown", UUID.fromString("00000000-0000-0000-0000-000000000000")), 0);
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(wrapped);
            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] encrypted = new byte[buf.remaining()];
            buf.get(encrypted);

            Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] keyBytes = cipher.doFinal(encrypted);
            SecretKey result = new SecretKeySpec(keyBytes, "AES");
            Arrays.fill(keyBytes, (byte) 0);
            return result;
        } catch (Exception e) {
            throw new KeyManagementUnavailableException("Failed to unwrap data key", e);
        }
    }

    private record SubjectKeyRow(int keyVersion, byte[] wrappedKey, String state) {}
}
