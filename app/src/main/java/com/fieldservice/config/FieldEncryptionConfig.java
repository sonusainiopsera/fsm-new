package com.fieldservice.config;

import com.fieldservice.platform.persistence.EncryptedStringConverter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * Initialises the static encryption key used by {@link EncryptedStringConverter}.
 *
 * <p>The key must be a base64-encoded 32-byte (256-bit) value supplied via
 * {@code app.crypto.field-encryption-key}. Fail-fast at startup when the property
 * is missing or the decoded length is not 32.
 */
@Configuration
public class FieldEncryptionConfig {

    @Value("${app.crypto.field-encryption-key}")
    private String base64Key;

    @PostConstruct
    void initFieldEncryption() {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        EncryptedStringConverter.configure(keyBytes);
    }
}
