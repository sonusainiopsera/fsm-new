package com.fieldservice.platform.privacy;

import java.util.Optional;

/**
 * Bridge interface allowing the masking layer (platform) to resolve the
 * {@link MaskingTier} for a given class/field without importing the privacy
 * module's {@code @DataClassification} annotation.
 *
 * <p>The implementation ({@code AnnotationFieldTierProvider}) lives in the
 * privacy module and is injected into platform beans via Spring's DI container.
 *
 * <p>Contract: implementations are read-only, thread-safe, and must not throw;
 * return {@link Optional#empty()} when the tier is unknown.
 */
@FunctionalInterface
public interface FieldTierProvider {

    /**
     * Returns the masking tier for the named field on {@code declaringClass},
     * or empty if no classification is found.
     *
     * @param declaringClass the class that declares the field (never null)
     * @param fieldName      the Java field name (never null or blank)
     */
    Optional<MaskingTier> getTier(Class<?> declaringClass, String fieldName);
}
