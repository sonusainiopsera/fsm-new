package com.fieldservice.platform.outbox;

import com.fieldservice.platform.outbox.annotation.Confidential;
import com.fieldservice.platform.outbox.annotation.Restricted;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PiiRedactionUtility.
 *
 * <p>Uses inline fixture records whose fields carry @Restricted and @Confidential annotations.
 */
class PiiRedactionUtilityTest {

    // --------------------------------------------------------------------------
    // Fixture payload records
    // --------------------------------------------------------------------------

    record CleanPayload(UUID id, String title, int count) {}

    record PayloadWithConfidential(
            UUID workOrderId,
            @Confidential String customerPhone,
            @Confidential String gpsCoordinate,
            String state
    ) {}

    record PayloadWithRestricted(
            UUID workOrderId,
            @Restricted String passwordHash,
            String state
    ) {}

    record MixedPayload(
            UUID id,
            @Restricted String apiToken,
            @Confidential String emailAddress,
            String status
    ) {}

    // --------------------------------------------------------------------------
    // 1. Clean payload — all fields pass through unchanged
    // --------------------------------------------------------------------------

    @Test
    void cleanPayloadAllFieldsIncluded() {
        UUID id = UUID.randomUUID();
        var map = PiiRedactionUtility.toPayloadMap(new CleanPayload(id, "HVAC repair", 3));

        assertThat(map).containsEntry("id", id)
                       .containsEntry("title", "HVAC repair")
                       .containsEntry("count", 3);
    }

    // --------------------------------------------------------------------------
    // 2. Confidential fields are masked, not omitted
    // --------------------------------------------------------------------------

    @Test
    void confidentialFieldsMaskedWithSentinel() {
        UUID id = UUID.randomUUID();
        var map = PiiRedactionUtility.toPayloadMap(
                new PayloadWithConfidential(id, "+44 20 7946 0958", "51.5074,-0.1278", "IN_PROGRESS"));

        assertThat(map)
                .containsEntry("workOrderId", id)
                .containsEntry("state", "IN_PROGRESS")
                .containsKey("customerPhone")
                .containsKey("gpsCoordinate");

        assertThat(map.get("customerPhone")).isEqualTo(PiiRedactionUtility.REDACTED_SENTINEL);
        assertThat(map.get("gpsCoordinate")).isEqualTo(PiiRedactionUtility.REDACTED_SENTINEL);
    }

    @Test
    void confidentialFieldKeyPresentButValueRedacted() {
        var map = PiiRedactionUtility.toPayloadMap(
                new PayloadWithConfidential(UUID.randomUUID(), "07700 900000", "53.4808,-2.2426", "NEW"));

        // Key present — consumer can see the field was withheld
        assertThat(map).containsKey("customerPhone");
        // Value is the redaction sentinel, not the actual phone number
        assertThat(map.get("customerPhone")).isNotEqualTo("07700 900000");
    }

    // --------------------------------------------------------------------------
    // 3. Restricted fields cause immediate fail-fast exception
    // --------------------------------------------------------------------------

    @Test
    void restrictedFieldThrowsRestrictedDataException() {
        assertThatThrownBy(() ->
                PiiRedactionUtility.toPayloadMap(
                        new PayloadWithRestricted(UUID.randomUUID(), "$2a$12$hash...", "ASSIGNED")))
                .isInstanceOf(RestrictedDataInPayloadException.class)
                .hasMessageContaining("passwordHash")
                .hasMessageContaining("Restricted");
    }

    @Test
    void mixedPayloadFailsFastOnRestrictedBeforeProcessingConfidential() {
        assertThatThrownBy(() ->
                PiiRedactionUtility.toPayloadMap(
                        new MixedPayload(UUID.randomUUID(), "sk-abcdef", "test@example.com", "OPEN")))
                .isInstanceOf(RestrictedDataInPayloadException.class)
                .hasMessageContaining("apiToken");
    }

    // --------------------------------------------------------------------------
    // 4. Null payload record throws immediately
    // --------------------------------------------------------------------------

    @Test
    void nullPayloadRecordThrowsIllegalArgument() {
        assertThatThrownBy(() -> PiiRedactionUtility.toPayloadMap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --------------------------------------------------------------------------
    // 5. Null field value is included as null (not rejected)
    // --------------------------------------------------------------------------

    @Test
    void nullFieldValueIncludedAsNull() {
        var map = PiiRedactionUtility.toPayloadMap(new CleanPayload(null, null, 0));
        assertThat(map).containsEntry("id", null);
        assertThat(map).containsEntry("title", null);
    }

    // --------------------------------------------------------------------------
    // 6. Field order preserved (LinkedHashMap)
    // --------------------------------------------------------------------------

    @Test
    void fieldOrderMatchesDeclaredOrder() {
        UUID id = UUID.randomUUID();
        var map = PiiRedactionUtility.toPayloadMap(new CleanPayload(id, "Test", 1));
        var keys = map.keySet().toArray(new String[0]);
        assertThat(keys).containsExactly("id", "title", "count");
    }
}
