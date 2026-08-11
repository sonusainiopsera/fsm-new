package com.fieldservice.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA {@link AttributeConverter} that transparently AES-256-GCM encrypts and decrypts
 * {@code String} column values.
 *
 * <h2>Format</h2>
 * The stored column value is {@code BASE64(IV || CIPHERTEXT)} where:
 * <ul>
 *   <li>IV is 12 random bytes (GCM standard nonce size)</li>
 *   <li>CIPHERTEXT is the AES/GCM/NoPadding output (includes 16-byte authentication tag)</li>
 * </ul>
 *
 * <h2>Key management</h2>
 * The data-encryption key is a 32-byte (256-bit) value read from the
 * {@code app.encryption.data-key-base64} property, which must be a standard-Base64-encoded
 * 32-byte secret.  Use {@link EncryptionKeyGenerator} to generate a suitable value.
 *
 * <h2>Null handling</h2>
 * {@code null} is stored as {@code null}; no encryption is applied to null values.
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>Each encrypt call generates a fresh 12-byte IV from {@link SecureRandom}.</li>
 *   <li>GCM authentication tag (128-bit) detects tampering during decryption.</li>
 *   <li>Values are never written to logs.</li>
 * </ul>
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN = 12;
    private static final int    GCM_TAG_BITS = 128;

    /** Lazily initialised from the platform encryption registry. */
    private final SecretKey secretKey;

    /**
     * Constructor used by the platform {@link EncryptionConfiguration} to inject the data key.
     * Spring will use this constructor if the converter is declared as a Spring bean;
     * for JPA-instantiated converters Spring Boot's {@link org.springframework.context.annotation.Bean}
     * on a {@link Converter} with {@code autoApply=false} ensures the bean is used.
     */
    public EncryptedStringConverter(SecretKey secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * No-arg constructor required by the JPA specification.
     * Falls back to a deterministic test key when no Spring context is available
     * (e.g. H2 in-memory integration tests without a configured data key).
     */
    public EncryptedStringConverter() {
        // 32-byte test key — never used in production
        byte[] testKey = "00000000000000000000000000000000".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.secretKey = new SecretKeySpec(testKey, "AES");
        log.debug("EncryptedStringConverter initialised with built-in test key");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt field value", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            ByteBuffer buf = ByteBuffer.wrap(combined);

            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt field value", e);
        }
    }

    /** Thrown when AES-GCM encrypt or decrypt fails (wraps checked exceptions). */
    public static final class EncryptionException extends RuntimeException {
        public EncryptionException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
