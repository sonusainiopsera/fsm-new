package com.fieldservice.platform.outbox;

import com.fieldservice.platform.outbox.annotation.Confidential;
import com.fieldservice.platform.outbox.annotation.Restricted;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a purpose-built payload record into a sanitised {@link Map} for event payload use.
 *
 * <p>The utility inspects every declared field (including record components) via reflection:
 * <ul>
 *   <li>Fields annotated {@link Restricted}: throw {@link RestrictedDataInPayloadException}
 *       immediately — fail-fast to prevent Restricted data (password hashes, tokens, credentials)
 *       from reaching the outbox, broker, or dead-letter queue.</li>
 *   <li>Fields annotated {@link Confidential}: include the field key in the payload map with
 *       value {@code "[REDACTED]"} so consumers can see the field was present but suppressed.</li>
 *   <li>All other fields: included with their actual value.</li>
 * </ul>
 *
 * <p>This utility is a defence-in-depth control. The primary control is the allow-list of fields
 * in the purpose-built payload record itself — only the fields explicitly declared in the record
 * can appear in the payload. This utility provides a runtime backstop against accidental inclusion
 * of classified data.
 *
 * <p>Usage:
 * <pre>{@code
 * var payload = PiiRedactionUtility.toPayloadMap(new WorkOrderStateChangedPayload(...));
 * var event = DomainEvent.of("WorkOrderStateChanged", "WorkOrder", workOrderId,
 *                             Instant.now(), traceId, actorId, payload);
 * publisher.publish(event);
 * }</pre>
 */
public final class PiiRedactionUtility {

    /** Sentinel value written for Confidential fields. */
    static final String REDACTED_SENTINEL = "[REDACTED]";

    private PiiRedactionUtility() {}

    /**
     * Converts a payload object into a sanitised {@code Map<String, Object>}.
     *
     * @param payloadRecord a purpose-built payload record (must not be null)
     * @return sanitised map: Restricted fields cause an exception; Confidential fields are masked
     * @throws RestrictedDataInPayloadException if any declared field carries {@link Restricted}
     * @throws IllegalArgumentException         if {@code payloadRecord} is null
     */
    public static Map<String, Object> toPayloadMap(Object payloadRecord) {
        if (payloadRecord == null) {
            throw new IllegalArgumentException("payloadRecord must not be null");
        }

        Class<?> type = payloadRecord.getClass();
        Map<String, Object> result = new LinkedHashMap<>();

        for (Field field : type.getDeclaredFields()) {
            // Skip synthetic fields (e.g. $assertionsDisabled in enum classes)
            if (field.isSynthetic()) {
                continue;
            }

            if (field.isAnnotationPresent(Restricted.class)) {
                throw new RestrictedDataInPayloadException(field.getName(), type);
            }

            field.setAccessible(true);
            Object value;
            try {
                value = field.get(payloadRecord);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access field '" + field.getName() + "' on "
                        + type.getSimpleName(), e);
            }

            if (field.isAnnotationPresent(Confidential.class)) {
                result.put(field.getName(), REDACTED_SENTINEL);
            } else {
                result.put(field.getName(), value);
            }
        }

        return result;
    }
}
