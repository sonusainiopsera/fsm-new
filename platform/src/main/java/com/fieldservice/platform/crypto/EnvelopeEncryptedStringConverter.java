package com.fieldservice.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * JPA {@link AttributeConverter} that provides per-data-subject AES-256-GCM envelope
 * encryption for Confidential PII columns (BR-23).
 *
 * <h2>Envelope format</h2>
 * Stored column value: {@code ENCv1|{keyVersion}|{subjectType}|{subjectId}|{base64(IV||ciphertext)}}
 * <ul>
 *   <li>{@code ENCv1} — magic prefix for format detection</li>
 *   <li>{@code keyVersion} — integer key version stored in the payload, enabling
 *       rotation without re-encrypting history</li>
 *   <li>{@code subjectType}, {@code subjectId} — subject reference for key lookup
 *       during decryption (no ThreadLocal needed on the read path)</li>
 *   <li>{@code base64(IV||ciphertext)} — 12-byte random IV + AES/GCM/NoPadding output
 *       including 128-bit authentication tag</li>
 * </ul>
 *
 * <h2>Legacy format compatibility</h2>
 * Values not prefixed with {@code ENCv1|} are treated as Phase-1 legacy ciphertext
 * ({@link EncryptedStringConverter} format) and decrypted using the global data key.
 *
 * <h2>Subject context</h2>
 * Encryption (write path) reads the current subject from {@link SubjectEncryptionContext}.
 * If no subject is set, the legacy global-key path is used, allowing gradual migration
 * without breaking existing tests or batch jobs.  Decryption (read path) is self-contained:
 * the subject reference is encoded in the stored value so no ThreadLocal is needed.
 *
 * <h2>Fail-closed contract</h2>
 * <ul>
 *   <li>Write with key-management unavailable: throws {@link KeyManagementUnavailableException}.
 *       Callers must map this to a 503-class response; plaintext is NEVER persisted as fallback.</li>
 *   <li>Read after key destruction: returns {@link #UNRECOVERABLE_MARKER}.</li>
 *   <li>Read with key-management unavailable: returns {@link #DEGRADED_MARKER}.</li>
 * </ul>
 */
@Converter
public class EnvelopeEncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeEncryptedStringConverter.class);

    /** Returned when a subject's key has been irrevocably destroyed. */
    public static final String UNRECOVERABLE_MARKER = "[UNRECOVERABLE]";

    /** Returned when the key-management service is unavailable during a read. */
    public static final String DEGRADED_MARKER = "[DEGRADED]";

    private static final String ENVELOPE_PREFIX = "ENCv1|";
    private static final String ALGORITHM        = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN       = 12;
    private static final int    GCM_TAG_BITS      = 128;

    private final SubjectKeyManager keyManager;
    private final SecretKey         legacyDataKey;

    public EnvelopeEncryptedStringConverter(SubjectKeyManager keyManager, SecretKey legacyDataKey) {
        this.keyManager    = keyManager;
        this.legacyDataKey = legacyDataKey;
    }

    /**
     * No-arg constructor required by the JPA specification.
     * Falls back to an in-memory key manager and all-zeros legacy key when no Spring
     * context is available (e.g. H2 integration tests not loading application context).
     */
    public EnvelopeEncryptedStringConverter() {
        byte[] zeroKey = new byte[32];
        this.legacyDataKey = new javax.crypto.spec.SecretKeySpec(zeroKey, "AES");
        this.keyManager    = new NoopSubjectKeyManager(this.legacyDataKey);
        log.debug("EnvelopeEncryptedStringConverter initialised with no-arg constructor (test mode)");
    }

    /** Minimal in-memory key manager used by the no-arg constructor fallback. */
    private static final class NoopSubjectKeyManager implements SubjectKeyManager {
        private final java.util.concurrent.ConcurrentHashMap<String, SubjectKeySpec> keys =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<String> destroyed =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        private final SecretKey masterKey;

        NoopSubjectKeyManager(SecretKey masterKey) { this.masterKey = masterKey; }

        @Override
        public SubjectKeySpec generate(SubjectRef subject) { return resolveActive(subject); }

        @Override
        public SubjectKeySpec resolveActive(SubjectRef subject) {
            return keys.computeIfAbsent(key(subject, 1),
                    k -> new SubjectKeySpec(masterKey, 1));
        }

        @Override
        public SubjectKeySpec resolve(SubjectRef subject, int keyVersion) {
            if (destroyed.contains(subject.subjectType() + subject.subjectId())) {
                throw new SubjectKeyDestroyedException(subject, keyVersion);
            }
            return new SubjectKeySpec(masterKey, keyVersion);
        }

        @Override public void rotate(SubjectRef subject) { /* no-op */ }

        @Override
        public void destroy(SubjectRef subject) {
            destroyed.add(subject.subjectType() + subject.subjectId());
        }

        private static String key(SubjectRef s, int v) {
            return s.subjectType() + "|" + s.subjectId() + "|" + v;
        }
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        SubjectEncryptionContext.current().ifPresentOrElse(
                subject -> { /* validated below */ },
                () -> log.debug("EnvelopeEncryptedStringConverter: no subject context — using legacy global key")
        );

        return SubjectEncryptionContext.current()
                .map(subject -> encryptEnvelope(plaintext, subject))
                .orElseGet(() -> encryptLegacy(plaintext));
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        if (UNRECOVERABLE_MARKER.equals(stored) || DEGRADED_MARKER.equals(stored)) {
            return stored;
        }
        if (stored.startsWith(ENVELOPE_PREFIX)) {
            return decryptEnvelope(stored);
        }
        return decryptLegacy(stored);
    }

    // ---- envelope path -----------------------------------------------------

    private String encryptEnvelope(String plaintext, SubjectRef subject) {
        try {
            SubjectKeySpec spec = keyManager.resolveActive(subject);

            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, spec.secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv).put(ciphertext).array();
            String base64Payload = Base64.getEncoder().encodeToString(combined);

            return ENVELOPE_PREFIX
                    + spec.keyVersion() + "|"
                    + subject.subjectType() + "|"
                    + subject.subjectId() + "|"
                    + base64Payload;

        } catch (KeyManagementUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptedStringConverter.EncryptionException("Envelope encryption failed", e);
        }
    }

    private String decryptEnvelope(String stored) {
        // Format: ENCv1|{keyVersion}|{subjectType}|{subjectId}|{base64}
        String body = stored.substring(ENVELOPE_PREFIX.length());
        String[] parts = body.split("\\|", 4);
        if (parts.length != 4) {
            log.error("Malformed envelope ciphertext — expected 4 parts after prefix, got {}", parts.length);
            return DEGRADED_MARKER;
        }
        try {
            int        keyVersion  = Integer.parseInt(parts[0]);
            String     subjectType = parts[1];
            UUID       subjectId   = UUID.fromString(parts[2]);
            String     base64      = parts[3];
            SubjectRef subject     = SubjectRef.of(subjectType, subjectId);

            SubjectKeySpec spec = keyManager.resolve(subject, keyVersion);

            byte[] combined = Base64.getDecoder().decode(base64);
            ByteBuffer buf  = ByteBuffer.wrap(combined);

            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, spec.secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        } catch (SubjectKeyDestroyedException e) {
            log.warn("Decrypt returning unrecoverable marker: {}", e.getMessage());
            return UNRECOVERABLE_MARKER;
        } catch (KeyManagementUnavailableException e) {
            log.warn("Key management unavailable during read: {}", e.getMessage());
            return DEGRADED_MARKER;
        } catch (NumberFormatException | IllegalArgumentException e) {
            log.error("Malformed envelope ciphertext — could not parse header: {}", e.getMessage());
            return DEGRADED_MARKER;
        } catch (Exception e) {
            log.error("Envelope decryption failed for stored value (key/subject info suppressed)", e);
            return DEGRADED_MARKER;
        }
    }

    // ---- legacy (Phase 1) path — global key --------------------------------

    private String encryptLegacy(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, legacyDataKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv).put(ciphertext).array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new EncryptedStringConverter.EncryptionException("Legacy encryption failed", e);
        }
    }

    private String decryptLegacy(String stored) {
        try {
            byte[]     combined = Base64.getDecoder().decode(stored);
            ByteBuffer buf      = ByteBuffer.wrap(combined);

            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, legacyDataKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptedStringConverter.EncryptionException("Legacy decryption failed", e);
        }
    }
}
