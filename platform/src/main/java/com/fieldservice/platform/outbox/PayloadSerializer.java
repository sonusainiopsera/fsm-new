package com.fieldservice.platform.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Dedicated Jackson serialiser for event payloads with strict settings and size bounding.
 *
 * <p>Uses an isolated {@link ObjectMapper} rather than the application-wide one so that
 * a future change to the global mapper (e.g. adding a module or changing inclusion rules)
 * cannot inadvertently alter the serialisation format of persisted outbox payloads.
 *
 * <p>Serialisation settings:
 * <ul>
 *   <li>Dates as ISO-8601 strings, not timestamps — human-readable in dead-letter tooling.</li>
 *   <li>Non-null inclusion — null values are omitted from the payload.</li>
 *   <li>JavaTimeModule — supports {@link java.time.Instant}, {@link java.time.LocalDate}, etc.</li>
 * </ul>
 */
final class PayloadSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private PayloadSerializer() {}

    /**
     * Serialises {@code payload} to a JSON string and enforces the byte-length bound.
     *
     * @param payload  sanitised payload map (must not be null)
     * @param maxBytes maximum allowed byte length of the serialised payload
     * @return JSON string representation of the payload
     * @throws PayloadTooLargeException if the serialised payload exceeds {@code maxBytes}
     * @throws RuntimeException         if Jackson serialisation fails unexpectedly
     */
    static String serialize(Map<String, Object> payload, int maxBytes) {
        String json;
        try {
            json = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialise event payload: " + e.getMessage(), e);
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new PayloadTooLargeException(bytes.length, maxBytes);
        }

        return json;
    }
}
