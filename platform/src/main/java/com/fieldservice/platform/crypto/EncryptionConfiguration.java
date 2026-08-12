package com.fieldservice.platform.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Wires the encryption infrastructure from configuration.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link EncryptedStringConverter} — Phase-1 global-key converter (retained for
 *       backward compatibility with pre-envelope ciphertext).</li>
 *   <li>{@link SubjectKeyCache} — bounded TTL in-memory key cache.</li>
 *   <li>{@link LocalStubSubjectKeyManager} — local key-wrapping manager; replace with
 *       a KMS-backed implementation in cloud deployments.</li>
 *   <li>{@link EnvelopeEncryptedStringConverter} — per-subject envelope converter
 *       (Phase 2; replaces direct use of {@link EncryptedStringConverter} on new fields).</li>
 *   <li>{@link BlindIndex} — HMAC-SHA-256 blind-index utility keyed separately from
 *       the data-encryption key.</li>
 * </ul>
 */
@Configuration
public class EncryptionConfiguration {

    // ---- global data key (Phase 1 and master-key for stub wrapping) --------

    @Bean
    @ConditionalOnMissingBean(SecretKey.class)
    public SecretKey encryptionDataKey(
            @Value("${app.encryption.data-key-base64:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
            String keyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "app.encryption.data-key-base64 must be a Base64-encoded 32-byte key; got "
                    + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    @Bean
    public EncryptedStringConverter encryptedStringConverter(SecretKey encryptionDataKey) {
        return new EncryptedStringConverter(encryptionDataKey);
    }

    // ---- per-subject envelope encryption (Phase 2) -------------------------

    @Bean
    public SubjectKeyCache subjectKeyCache() {
        return new SubjectKeyCache();
    }

    @Bean
    @ConditionalOnMissingBean(SubjectKeyManager.class)
    public SubjectKeyManager subjectKeyManager(JdbcTemplate jdbcTemplate,
                                               SecretKey encryptionDataKey,
                                               SubjectKeyCache subjectKeyCache) {
        return new LocalStubSubjectKeyManager(jdbcTemplate, encryptionDataKey, subjectKeyCache);
    }

    @Bean
    public EnvelopeEncryptedStringConverter envelopeEncryptedStringConverter(
            SubjectKeyManager subjectKeyManager,
            SecretKey encryptionDataKey) {
        return new EnvelopeEncryptedStringConverter(subjectKeyManager, encryptionDataKey);
    }

    // ---- blind-index utility -----------------------------------------------

    @Bean
    public BlindIndex blindIndex(
            @Value("${app.encryption.blind-index-key-base64:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
            String indexKeyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(indexKeyBase64);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "app.encryption.blind-index-key-base64 must be at least 32 bytes; got "
                    + keyBytes.length);
        }
        return new BlindIndex(keyBytes);
    }
}
