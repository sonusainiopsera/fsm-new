package com.fieldservice.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * JPA {@link AttributeConverter} implementing per-subject envelope encryption.
 *
 * <p>Each plaintext value is encrypted with AES-256-GCM using a per-data-subject
 * data-encryption key (DEK) obtained from {@link SubjectKeyManager}. The encrypted
 * envelope is base64-encoded and stored in a text column.
 *
 * <h3>Envelope format (binary, before base64 encoding)</h3>
 * <pre>
 *   Offset  Size  Description
 *   ──────  ────  ────────────────────────────────────────────────────────
 *     0       1   Version byte (0x01)
 *     1       8   Subject UUID most-significant bits (big-endian int64)
 *     9       8   Subject UUID least-significant bits (big-endian int64)
 *    17       4   Key version (big-endian int32)
 *    21      12   AES-GCM IV (random, unique per write)
 *    33      var  AES-256-GCM ciphertext + 16-byte GCM auth tag
 * </pre>
 *
 * <p>Embedding subject ID and key version makes the decrypt path self-contained:
 * no external context is needed when reading back from the database.
 *
 * <h3>Fail-closed behaviour</h3>
 * <ul>
 *   <li>Write — key manager not configured → {@link IllegalStateException}</li>
 *   <li>Write — KMS unavailable → {@link KeyManagementUnavailableException} (never writes plaintext)</li>
 *   <li>Write — no subject context → {@link IllegalStateException}</li>
 *   <li>Read — destroyed key → {@link #UNRECOVERABLE_MARKER} (never throws)</li>
 *   <li>Read — KMS unavailable → {@link #DEGRADED_MARKER} (never throws)</li>
 * </ul>
 *
 * <h3>Transition compatibility</h3>
 * <p>Ciphertext from the legacy {@code EncryptedStringConverter} (format
 * {@code base64iv.base64ciphertext}) contains a {@code "."} separator. During the
 * migration window these values pass through unchanged so the backfill step can
 * detect and re-encrypt them.
 */
@Converter
public class EnvelopeEncryptedStringConverter implements AttributeConverter<String, String> {

    /** Returned when the subject key has been destroyed. */
    public static final String UNRECOVERABLE_MARKER = "[UNRECOVERABLE]";

    /** Returned when the key-management service is unavailable during a read. */
    public static final String DEGRADED_MARKER = "[KMS_DEGRADED]";

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS  = 128;
    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final byte VERSION_BYTE = 0x01;

    private static volatile SubjectKeyManager keyManager;

    /** Called once at startup by {@code EnvelopeEncryptionConfig}. */
    public static void configure(SubjectKeyManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("SubjectKeyManager must not be null");
        }
        keyManager = manager;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        SubjectKeyManager km = requireKeyManager();

        SubjectKeyContext.EncryptionContext ctx = SubjectKeyContext.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "EnvelopeEncryptedStringConverter: no subject context is set. "
                    + "Ensure the entity implements SubjectKeyContextProvider and is "
                    + "annotated with @EntityListeners(SubjectKeyContextListener.class).");
        }

        DataKey dk = km.getOrCreateKey(ctx.subjectType(), ctx.subjectId());

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(dk.keyMaterial(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = buildEnvelope(ctx.subjectId(), dk.version(), iv, ciphertext);
            return Base64.getEncoder().encodeToString(envelope);

        } catch (KeyManagementUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new KeyManagementUnavailableException(
                    "EnvelopeEncryptedStringConverter: encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;

        // Legacy format — contains a dot separator; pass through for backfill detection
        if (dbData.contains(".")) return dbData;

        SubjectKeyManager km = keyManager;
        if (km == null) return DEGRADED_MARKER;

        try {
            byte[] envelope = Base64.getDecoder().decode(dbData);
            ByteBuffer buf = ByteBuffer.wrap(envelope);

            byte version = buf.get();
            if (version != VERSION_BYTE) return DEGRADED_MARKER;

            long msb = buf.getLong();
            long lsb = buf.getLong();
            UUID subjectId = new UUID(msb, lsb);
            int keyVersion = buf.getInt();

            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            DataKey dk = km.resolveKeyById(subjectId, keyVersion);

            if (dk.state() == SubjectKeyState.DESTROYED) {
                return UNRECOVERABLE_MARKER;
            }

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(dk.keyMaterial(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        } catch (KeyManagementUnavailableException e) {
            return DEGRADED_MARKER;
        } catch (Exception e) {
            return DEGRADED_MARKER;
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private static SubjectKeyManager requireKeyManager() {
        SubjectKeyManager km = keyManager;
        if (km == null) {
            throw new IllegalStateException(
                    "EnvelopeEncryptedStringConverter: key manager not configured. "
                    + "Ensure EnvelopeEncryptionConfig has been initialised before any "
                    + "encrypted field is accessed.");
        }
        return km;
    }

    private static byte[] buildEnvelope(UUID subjectId, int keyVersion, byte[] iv, byte[] ciphertext) {
        // 1 (version) + 8 (msb) + 8 (lsb) + 4 (keyVersion) + 12 (iv) + ciphertext
        ByteBuffer buf = ByteBuffer.allocate(33 + ciphertext.length);
        buf.put(VERSION_BYTE);
        buf.putLong(subjectId.getMostSignificantBits());
        buf.putLong(subjectId.getLeastSignificantBits());
        buf.putInt(keyVersion);
        buf.put(iv);
        buf.put(ciphertext);
        return buf.array();
    }
}
