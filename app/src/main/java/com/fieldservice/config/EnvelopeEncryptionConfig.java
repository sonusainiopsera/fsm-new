package com.fieldservice.config;

import com.fieldservice.platform.crypto.BlindIndex;
import com.fieldservice.platform.crypto.EnvelopeEncryptedStringConverter;
import com.fieldservice.platform.crypto.LocalStubKeyManager;
import com.fieldservice.platform.crypto.SubjectKeyManager;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Base64;

/**
 * Configures the per-subject envelope encryption infrastructure (WO-193).
 *
 * <p>Wires the {@link SubjectKeyManager} into {@link EnvelopeEncryptedStringConverter} and
 * configures the {@link BlindIndex} with its dedicated HMAC key. Both keys must be supplied
 * as base64-encoded 32-byte (256-bit) values via application properties.
 *
 * <p>In the absence of a production-grade {@link SubjectKeyManager} bean (e.g. KMS-backed),
 * the {@link LocalStubKeyManager} is auto-registered for non-production profiles.
 */
@Configuration
public class EnvelopeEncryptionConfig {

    @Value("${app.crypto.blind-index-key}")
    private String base64BlindIndexKey;

    @Autowired
    private SubjectKeyManager subjectKeyManager;

    /**
     * Fallback {@link SubjectKeyManager} for local development and tests.
     * Activated when no other SubjectKeyManager bean is present.
     * Must never run under {@code api} or {@code worker} profiles.
     */
    @Bean
    @ConditionalOnMissingBean(SubjectKeyManager.class)
    @Profile("!(api | worker)")
    SubjectKeyManager localStubKeyManager() {
        return new LocalStubKeyManager();
    }

    @PostConstruct
    void initEnvelopeEncryption() {
        EnvelopeEncryptedStringConverter.configure(subjectKeyManager);

        byte[] blindIndexKeyBytes = Base64.getDecoder().decode(base64BlindIndexKey);
        if (blindIndexKeyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "app.crypto.blind-index-key must decode to exactly 32 bytes (AES-256)");
        }
        BlindIndex.configure(blindIndexKeyBytes);
    }
}
