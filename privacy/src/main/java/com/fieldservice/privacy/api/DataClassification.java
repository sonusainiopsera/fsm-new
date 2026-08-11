package com.fieldservice.privacy.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the data classification tier for a JPA entity class or field.
 *
 * <p>This annotation is a <em>compile-time assertion</em>: the tier assignment is the
 * authoritative single source of truth in the {@code data_classification} database table.
 * The {@code ClassificationConsistencyCheck} ApplicationRunner compares annotated elements
 * against registry rows at startup and fails fast on any drift.
 *
 * <p>Usage:
 * <pre>{@code
 * @DataClassification(value = ClassificationTier.CONFIDENTIAL, module = "identity")
 * public class AppUser { ... }
 *
 * @DataClassification(ClassificationTier.RESTRICTED)
 * private String passwordHash;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface DataClassification {

    /** The classification tier for this element. */
    ClassificationTier value();

    /**
     * Module name that owns this entity (e.g. "identity", "workorder").
     * Used in the consistency check to provide actionable error messages.
     */
    String module() default "";

    /** Optional note explaining the classification rationale; not persisted. */
    String note() default "";
}
