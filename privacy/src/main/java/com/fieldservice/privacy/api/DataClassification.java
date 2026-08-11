package com.fieldservice.privacy.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a JPA entity class or field to a {@link ClassificationTier}.
 *
 * <p>Applying this annotation is a compile-time assertion that the corresponding row
 * exists in the {@code data_classification} table. The
 * {@code ClassificationConsistencyCheck} ApplicationRunner compares all annotated
 * elements against the registry at startup and fails fast when the two are out of sync.
 *
 * <p>Usage:
 * <pre>{@code
 * @DataClassification(tier = ClassificationTier.CONFIDENTIAL)
 * @Entity
 * public class Customer extends BaseEntity { ... }
 *
 * @DataClassification(tier = ClassificationTier.RESTRICTED)
 * private String passwordHash;
 * }</pre>
 *
 * <p>Applying to a type covers the entity as a whole; applying to a field narrows the
 * classification to that field. Both may coexist on the same entity (entity-level
 * INTERNAL + field-level RESTRICTED for a credential column is the canonical example).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface DataClassification {

    /** The classification tier for this entity or field. */
    ClassificationTier tier();

    /** Optional human-readable note explaining the classification decision. */
    String note() default "";
}
