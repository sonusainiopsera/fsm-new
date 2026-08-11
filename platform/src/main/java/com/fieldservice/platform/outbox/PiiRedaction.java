package com.fieldservice.platform.outbox;

import java.lang.reflect.Field;

/**
 * PII redaction utility applied to payload records before outbox serialisation.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@link Restricted} fields must be null — non-null throws {@link RestrictedFieldException}
 *       and aborts the transaction.</li>
 *   <li>{@link Confidential} {@code String} fields are replaced with {@code "***"}.</li>
 *   <li>{@link Confidential} non-String fields (numeric coordinates, objects) are set to
 *       {@code null}.</li>
 * </ul>
 *
 * <p>The check is reflective and traverses all declared fields of the payload class,
 * including fields inherited from parent classes.
 */
public final class PiiRedaction {

    private PiiRedaction() {}

    /**
     * Sanitises {@code payload} in-place: fails fast on Restricted data, masks Confidential data.
     *
     * @param payload a mutable purpose-built payload record/object
     * @throws RestrictedFieldException if any {@code @Restricted} field is non-null
     */
    public static void sanitize(Object payload) {
        if (payload == null) return;
        Class<?> cls = payload.getClass();
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    if (field.isAnnotationPresent(Restricted.class)) {
                        Object value = field.get(payload);
                        if (value != null) {
                            throw new RestrictedFieldException(field.getName());
                        }
                    } else if (field.isAnnotationPresent(Confidential.class)) {
                        if (field.getType() == String.class) {
                            field.set(payload, "***");
                        } else {
                            field.set(payload, null);
                        }
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "Cannot access field '" + field.getName() + "' on " + cls.getName(), e);
                }
            }
            cls = cls.getSuperclass();
        }
    }

    /**
     * Returns {@code true} if the payload class declares any {@code @Restricted} fields,
     * useful in test assertions that verify the payload contract.
     */
    public static boolean hasRestrictedFields(Class<?> payloadClass) {
        Class<?> cls = payloadClass;
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.isAnnotationPresent(Restricted.class)) return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }
}
