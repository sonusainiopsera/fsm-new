package com.fieldservice.platform.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA {@link AttributeConverter} that encrypts String fields using AES-256-GCM.
 *
 * <p>The encryption key is configured at application startup via
 * {@link #configure(byte[])}. The stored format is
 * {@code base64(iv) + "." + base64(ciphertext+authTag)}, making ciphertexts
 * non-deterministic (fresh IV per write) and authenticated.
 *
 * <p>If the key is not configured when a conversion is attempted, the converter
 * throws an {@link IllegalStateException} rather than persisting plaintext, as
 * required by AC-6.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final int GCM_IV_LENGTH   = 12;
    private static final int GCM_TAG_BITS    = 128;
    private static final String ALGORITHM    = "AES/GCM/NoPadding";

    private static volatile SecretKey activeKey;

    /** Called once at startup by {@code FieldEncryptionConfig}. */
    public static void configure(byte[] rawKeyBytes) {
        if (rawKeyBytes == null || rawKeyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "Field encryption key must be exactly 32 bytes (AES-256)");
        }
        activeKey = new SecretKeySpec(rawKeyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        SecretKey sk = requireKey();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, sk, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv)
                    + "."
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Field encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        SecretKey sk = requireKey();
        try {
            int dot = dbData.indexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException("Encrypted field is not in iv.ciphertext format");
            }
            byte[] iv         = Base64.getDecoder().decode(dbData.substring(0, dot));
            byte[] ciphertext = Base64.getDecoder().decode(dbData.substring(dot + 1));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Field decryption failed", e);
        }
    }

    private static SecretKey requireKey() {
        SecretKey sk = activeKey;
        if (sk == null) {
            throw new IllegalStateException(
                    "EncryptedStringConverter: encryption key not configured. "
                    + "Ensure FieldEncryptionConfig has been initialised before any encrypted "
                    + "field is accessed.");
        }
        return sk;
    }
}
