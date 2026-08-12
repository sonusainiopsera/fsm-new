package com.fieldservice.privacy.internal;

import com.fieldservice.platform.privacy.FieldTierProvider;
import com.fieldservice.platform.privacy.MaskingTier;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@link MaskingTier} from {@link DataClassification} annotations found on
 * the class or field via reflection.
 *
 * <p>Field-level annotation takes precedence over class-level; class-level acts as the
 * default for all unannotated fields on that class.
 *
 * <p>Results are cached per (class, fieldName) pair to avoid repeated reflection overhead.
 */
@Component
class AnnotationFieldTierProvider implements FieldTierProvider {

    private final ConcurrentHashMap<String, Optional<MaskingTier>> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<MaskingTier> getTier(Class<?> declaringClass, String fieldName) {
        if (declaringClass == null || fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }
        String key = declaringClass.getName() + '#' + fieldName;
        return cache.computeIfAbsent(key, k -> resolve(declaringClass, fieldName));
    }

    private Optional<MaskingTier> resolve(Class<?> cls, String fieldName) {
        // 1. Field-level annotation (walk class hierarchy)
        Field field = findField(cls, fieldName);
        if (field != null && field.isAnnotationPresent(DataClassification.class)) {
            return Optional.of(toMaskingTier(field.getAnnotation(DataClassification.class).value()));
        }

        // 2. Class-level annotation on the declaring class
        if (cls.isAnnotationPresent(DataClassification.class)) {
            return Optional.of(toMaskingTier(cls.getAnnotation(DataClassification.class).value()));
        }

        return Optional.empty();
    }

    private static Field findField(Class<?> cls, String fieldName) {
        Class<?> cursor = cls;
        while (cursor != null && cursor != Object.class) {
            try {
                return cursor.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static MaskingTier toMaskingTier(ClassificationTier tier) {
        return switch (tier) {
            case PUBLIC       -> MaskingTier.PUBLIC;
            case INTERNAL     -> MaskingTier.INTERNAL;
            case CONFIDENTIAL -> MaskingTier.CONFIDENTIAL;
            case RESTRICTED   -> MaskingTier.RESTRICTED;
        };
    }
}
