package com.fieldservice.platform.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EnvelopeEncryptedStringConverter}.
 *
 * <p>Uses an in-memory {@link StubSubjectKeyManager} so no Spring context or database
 * is required.  All assertions run with a deterministic test key.
 */
class EnvelopeEncryptedStringConverterTest {

    private static final SubjectRef SUBJECT =
            SubjectRef.of("TECHNICIAN", UUID.fromString("00000000-0000-0000-0000-000000000001"));

    private StubSubjectKeyManager       keyManager;
    private EnvelopeEncryptedStringConverter converter;

    @BeforeEach
    void setUp() throws Exception {
        SecretKey legacyKey = buildTestKey();
        keyManager = new StubSubjectKeyManager();
        converter  = new EnvelopeEncryptedStringConverter(keyManager, legacyKey);
    }

    // ---- null / empty handling ------------------------------------------------

    @Test
    @DisplayName("null plaintext produces null stored value")
    void null_plaintext_produces_null_stored() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("null stored value produces null entity attribute")
    void null_stored_produces_null_attribute() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    // ---- envelope encrypt/decrypt round-trip ----------------------------------

    @Test
    @DisplayName("Encrypt/decrypt round trip with subject context")
    void round_trip_with_subject_context() {
        String plaintext = "alice@example.com";

        String stored = SubjectEncryptionContext.callWithUnchecked(SUBJECT,
                () -> converter.convertToDatabaseColumn(plaintext));

        assertThat(stored).startsWith("ENCv1|");

        String decrypted = converter.convertToEntityAttribute(stored);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Two encryptions of the same plaintext produce different ciphertext (random IV)")
    void iv_uniqueness() {
        String plaintext = "alice@example.com";

        String stored1 = SubjectEncryptionContext.callWithUnchecked(SUBJECT,
                () -> converter.convertToDatabaseColumn(plaintext));
        String stored2 = SubjectEncryptionContext.callWithUnchecked(SUBJECT,
                () -> converter.convertToDatabaseColumn(plaintext));

        assertThat(stored1).isNotEqualTo(stored2);
        assertThat(converter.convertToEntityAttribute(stored1)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(stored2)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Cross-version decryption: ciphertext encrypted under version 1 is readable after rotation to version 2")
    void cross_version_decryption() {
        String plaintext = "bob@example.com";

        String storedV1 = SubjectEncryptionContext.callWithUnchecked(SUBJECT,
                () -> converter.convertToDatabaseColumn(plaintext));

        assertThat(storedV1).contains("|1|");

        keyManager.rotate(SUBJECT);

        String storedV2 = SubjectEncryptionContext.callWithUnchecked(SUBJECT,
                () -> converter.convertToDatabaseColumn(plaintext));

        assertThat(storedV2).contains("|2|");
        assertThat(converter.convertToEntityAttribute(storedV1)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(storedV2)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Decrypt after destroy returns unrecoverable marker, never throws")
    void decrypt_after_destroy_returns_marker() {
        String plaintext = "carol@example.com";

        String stored = SubjectEncryptionContext.callWithUnchecked(SUBJECT,
                () -> converter.convertToDatabaseColumn(plaintext));

        keyManager.destroy(SUBJECT);

        String result = converter.convertToEntityAttribute(stored);
        assertThat(result).isEqualTo(EnvelopeEncryptedStringConverter.UNRECOVERABLE_MARKER);
    }

    @Test
    @DisplayName("Unrecoverable marker stored value is returned as-is")
    void unrecoverable_marker_passthrough() {
        String result = converter.convertToEntityAttribute(
                EnvelopeEncryptedStringConverter.UNRECOVERABLE_MARKER);
        assertThat(result).isEqualTo(EnvelopeEncryptedStringConverter.UNRECOVERABLE_MARKER);
    }

    @Test
    @DisplayName("Degraded marker stored value is returned as-is")
    void degraded_marker_passthrough() {
        String result = converter.convertToEntityAttribute(
                EnvelopeEncryptedStringConverter.DEGRADED_MARKER);
        assertThat(result).isEqualTo(EnvelopeEncryptedStringConverter.DEGRADED_MARKER);
    }

    // ---- legacy path (no subject context) ------------------------------------

    @Test
    @DisplayName("Legacy round-trip: encrypt without subject context uses global key, can be decrypted back")
    void legacy_round_trip_no_context() {
        String plaintext = "legacy-value";
        String stored    = converter.convertToDatabaseColumn(plaintext);

        assertThat(stored).doesNotStartWith("ENCv1|");

        String decrypted = converter.convertToEntityAttribute(stored);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ---- malformed envelope ---------------------------------------------------

    @Test
    @DisplayName("Malformed envelope header returns degraded marker")
    void malformed_envelope_returns_degraded() {
        String malformed = "ENCv1|notanumber|TECHNICIAN|not-a-uuid|payload";
        assertThat(converter.convertToEntityAttribute(malformed))
                .isEqualTo(EnvelopeEncryptedStringConverter.DEGRADED_MARKER);
    }

    // ---- helpers --------------------------------------------------------------

    private static SecretKey buildTestKey() throws Exception {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * Minimal in-memory stub for tests — no JDBC, no Spring.
     */
    static class StubSubjectKeyManager implements SubjectKeyManager {

        private final java.util.Map<SubjectRef, java.util.List<SubjectKeySpec>> keys =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<SubjectRef> destroyed =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

        @Override
        public SubjectKeySpec generate(SubjectRef subject) {
            return resolveActive(subject);
        }

        @Override
        public SubjectKeySpec resolveActive(SubjectRef subject) {
            keys.computeIfAbsent(subject, k -> new java.util.ArrayList<>());
            java.util.List<SubjectKeySpec> versions = keys.get(subject);
            if (versions.isEmpty()) {
                versions.add(buildSpec(1));
            }
            return versions.get(versions.size() - 1);
        }

        @Override
        public SubjectKeySpec resolve(SubjectRef subject, int keyVersion) {
            if (destroyed.contains(subject)) {
                throw new SubjectKeyDestroyedException(subject, keyVersion);
            }
            java.util.List<SubjectKeySpec> versions = keys.get(subject);
            if (versions != null) {
                for (SubjectKeySpec s : versions) {
                    if (s.keyVersion() == keyVersion) return s;
                }
            }
            throw new IllegalStateException("Key version not found: " + keyVersion);
        }

        @Override
        public void rotate(SubjectRef subject) {
            java.util.List<SubjectKeySpec> versions =
                    keys.computeIfAbsent(subject, k -> new java.util.ArrayList<>());
            int nextVersion = versions.isEmpty() ? 1 : versions.get(versions.size() - 1).keyVersion() + 1;
            versions.add(buildSpec(nextVersion));
        }

        @Override
        public void destroy(SubjectRef subject) {
            destroyed.add(subject);
        }

        private SubjectKeySpec buildSpec(int version) {
            try {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(256, new SecureRandom());
                return new SubjectKeySpec(kg.generateKey(), version);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
