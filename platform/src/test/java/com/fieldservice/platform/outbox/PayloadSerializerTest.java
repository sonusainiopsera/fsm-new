package com.fieldservice.platform.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PayloadSerializer — serialisation correctness and size bounding.
 */
class PayloadSerializerTest {

    // --------------------------------------------------------------------------
    // 1. Successful serialisation
    // --------------------------------------------------------------------------

    @Test
    void serialisesMapToJsonString() {
        var payload = Map.of("key", "value", "count", 42);
        String json = PayloadSerializer.serialize(payload, 65536);
        assertThat(json).contains("\"key\"").contains("\"value\"").contains("42");
    }

    @Test
    void serialisesInstantAsIso8601NotTimestamp() {
        Instant ts = Instant.parse("2026-08-11T12:00:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", ts);
        String json = PayloadSerializer.serialize(payload, 65536);
        // ISO-8601 format, not a numeric timestamp
        assertThat(json).contains("2026-08-11T12:00:00Z");
        assertThat(json).doesNotContain("1755172800");
    }

    @Test
    void nullValuesOmittedFromJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("present", "yes");
        payload.put("absent", null);
        String json = PayloadSerializer.serialize(payload, 65536);
        assertThat(json).contains("\"present\"");
        assertThat(json).doesNotContain("\"absent\"");
    }

    // --------------------------------------------------------------------------
    // 2. Size bounding
    // --------------------------------------------------------------------------

    @Test
    void payloadWithinBoundSucceeds() {
        var payload = Map.of("id", "abc123");
        // Should succeed without exception
        String json = PayloadSerializer.serialize(payload, 65536);
        assertThat(json).isNotBlank();
    }

    @Test
    void payloadExceedingBoundThrowsPayloadTooLarge() {
        // Build a payload that exceeds 100 bytes
        String largeValue = "x".repeat(200);
        Map<String, Object> payload = Map.of("bigField", largeValue);

        assertThatThrownBy(() -> PayloadSerializer.serialize(payload, 100))
                .isInstanceOf(PayloadTooLargeException.class)
                .satisfies(e -> {
                    PayloadTooLargeException pte = (PayloadTooLargeException) e;
                    assertThat(pte.getActualBytes()).isGreaterThan(100);
                    assertThat(pte.getMaxBytes()).isEqualTo(100);
                })
                .hasMessageContaining("100");
    }

    @Test
    void payloadExactlyAtBoundSucceeds() {
        var payload = Map.of("a", "b");
        String json = PayloadSerializer.serialize(payload, 65536);
        int actualBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // Should succeed when exactly at the boundary
        assertThat(PayloadSerializer.serialize(payload, actualBytes)).isNotBlank();
    }

    @Test
    void payloadOneByteBeyondBoundThrows() {
        var payload = Map.of("a", "b");
        String json = PayloadSerializer.serialize(payload, 65536);
        int actualBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        assertThatThrownBy(() -> PayloadSerializer.serialize(payload, actualBytes - 1))
                .isInstanceOf(PayloadTooLargeException.class);
    }
}
