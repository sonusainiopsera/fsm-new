package com.fieldservice.platform.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EnvelopeEncryptedStringConverter} (WO-193, AC-12).
 *
 * <p>Uses {@link LocalStubKeyManager} so no real KMS is required.
 */
@DisplayName("EnvelopeEncryptedStringConverter unit tests")
class EnvelopeEncryptedStringConverterTest {

    private static final UUID SUBJECT_ID = UUID.fromString("aa000000-0000-7000-8000-000000000001");
    private static final String SUBJECT_TYPE = "TEST_SUBJECT";
    private static final String PLAINTEXT = "test@example.com";

    private LocalStubKeyManager keyManager;
    private EnvelopeEncryptedStringConverter converter;

    @BeforeEach
    void setUp() {
        keyManager = new LocalStubKeyManager();
        EnvelopeEncryptedStringConverter.configure(keyManager);
        converter  = new EnvelopeEncryptedStringConverter();
        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
    }

    @Test
    @DisplayName("null input returns null — no ciphertext fabricated")
    void nullInput_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("round-trip: encrypt then decrypt returns original plaintext")
    void roundTrip_returnsOriginalPlaintext() {
        String ciphertext = converter.convertToDatabaseColumn(PLAINTEXT);
        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID); // reset after potential clear
        String decrypted = converter.convertToEntityAttribute(ciphertext);
        assertThat(decrypted).isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("IV uniqueness: two encryptions of the same plaintext produce different ciphertext")
    void ivUniqueness_differentCiphertextEachTime() {
        String c1 = converter.convertToDatabaseColumn(PLAINTEXT);
        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
        String c2 = converter.convertToDatabaseColumn(PLAINTEXT);
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    @DisplayName("cross-version decryption: ciphertext from v1 still decrypts after rotation to v2")
    void crossVersionDecryption_v1CiphertextDecryptsAfterRotation() {
        String v1Ciphertext = converter.convertToDatabaseColumn(PLAINTEXT);
        // Rotate to version 2
        keyManager.rotate(SUBJECT_TYPE, SUBJECT_ID);
        assertThat(keyManager.currentVersion(SUBJECT_TYPE, SUBJECT_ID)).isEqualTo(2);

        // Old ciphertext still decrypts
        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
        String decrypted = converter.convertToEntityAttribute(v1Ciphertext);
        assertThat(decrypted).isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("post-destroy: decrypt returns UNRECOVERABLE_MARKER, never throws")
    void postDestroy_returnsUnrecoverableMarker() {
        String ciphertext = converter.convertToDatabaseColumn(PLAINTEXT);
        keyManager.destroy(SUBJECT_TYPE, SUBJECT_ID);

        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
        String result = converter.convertToEntityAttribute(ciphertext);
        assertThat(result).isEqualTo(EnvelopeEncryptedStringConverter.UNRECOVERABLE_MARKER);
    }

    @Test
    @DisplayName("no subject context on write: throws IllegalStateException (fail-closed)")
    void noSubjectContext_throwsOnEncrypt() {
        SubjectKeyContext.clear();
        assertThatThrownBy(() -> converter.convertToDatabaseColumn(PLAINTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no subject context");
    }

    @Test
    @DisplayName("KMS unavailable on read: returns DEGRADED_MARKER, never throws")
    void kmsUnavailableOnRead_returnsDegradedMarker() {
        // Encrypt with a valid key manager
        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
        String ciphertext = converter.convertToDatabaseColumn(PLAINTEXT);

        // Replace with a key manager that simulates KMS outage on resolve
        EnvelopeEncryptedStringConverter.configure(new LocalStubKeyManager() {
            @Override
            public DataKey resolveKeyById(UUID subjectId, int keyVersion) {
                throw new KeyManagementUnavailableException("KMS offline");
            }
        });

        String result = converter.convertToEntityAttribute(ciphertext);
        assertThat(result).isEqualTo(EnvelopeEncryptedStringConverter.DEGRADED_MARKER);

        // Restore for subsequent tests
        EnvelopeEncryptedStringConverter.configure(keyManager);
    }

    @Test
    @DisplayName("legacy format (dot-separated) passes through unchanged for backfill detection")
    void legacyFormat_passesThrough() {
        String legacyValue = "dGVzdA==.dGVzdA=="; // base64(test).base64(test)
        String result = converter.convertToEntityAttribute(legacyValue);
        assertThat(result).isEqualTo(legacyValue);
    }

    @Test
    @DisplayName("SubjectKeyCache reduces key manager calls for same subject")
    void cache_reducesKeyManagerCallsForSameSubject() {
        LocalStubKeyManager spied = keyManager;
        int initialCount = spied.getResolveCallCount();

        // Encrypt to create the key
        String c1 = converter.convertToDatabaseColumn(PLAINTEXT);
        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
        String c2 = converter.convertToDatabaseColumn("other@example.com");

        // Decrypt both — should hit cache for the second call
        converter.convertToEntityAttribute(c1);
        converter.convertToEntityAttribute(c2);

        // The second decrypt should be a cache hit (resolve call count should be low)
        // Since LocalStubKeyManager uses its own cache, resolve calls are incremented on cache-miss only
        assertThat(spied.getResolveCallCount() - initialCount).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("cache evicted on rotate: next decrypt fetches fresh key from manager")
    void cacheEvictedOnRotate_fetchesFreshKey() {
        String ciphertext = converter.convertToDatabaseColumn(PLAINTEXT);
        int countBefore = keyManager.getResolveCallCount();

        keyManager.rotate(SUBJECT_TYPE, SUBJECT_ID);
        // Cache evicted for this subject

        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
        String decrypted = converter.convertToEntityAttribute(ciphertext);
        assertThat(decrypted).isEqualTo(PLAINTEXT);
        assertThat(keyManager.getResolveCallCount()).isGreaterThan(countBefore);
    }

    @Test
    @DisplayName("empty string round-trips correctly")
    void emptyString_roundTripsCorrectly() {
        String ciphertext = converter.convertToDatabaseColumn("");
        SubjectKeyContext.set(SUBJECT_TYPE, SUBJECT_ID);
        assertThat(converter.convertToEntityAttribute(ciphertext)).isEqualTo("");
    }
}
