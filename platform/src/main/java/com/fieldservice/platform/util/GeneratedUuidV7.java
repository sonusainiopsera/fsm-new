package com.fieldservice.platform.util;

import org.hibernate.annotations.IdGeneratorType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a UUID {@code @Id} field to be generated using the {@link UuidV7Generator},
 * which produces RFC 9562 UUIDv7 identifiers with:
 * <ul>
 *   <li>48-bit millisecond timestamp prefix for B-tree index locality</li>
 *   <li>Per-millisecond monotonic sequence for high-throughput insert ordering</li>
 *   <li>62-bit cryptographically random suffix for non-guessability</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * {@literal @}Id
 * {@literal @}GeneratedUuidV7
 * {@literal @}Column(name = "id", updatable = false)
 * private UUID id;
 * </pre>
 */
@IdGeneratorType(UuidV7Generator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@Documented
public @interface GeneratedUuidV7 {
}
