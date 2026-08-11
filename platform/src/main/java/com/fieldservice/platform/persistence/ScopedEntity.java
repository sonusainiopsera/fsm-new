package com.fieldservice.platform.persistence;

/**
 * Marker interface for JPA entities that require row-level scope enforcement.
 *
 * <p>Every entity implementing this interface must have a corresponding
 * {@link com.fieldservice.platform.security.AccessScopePredicateFactory} registration
 * contributed by a {@link com.fieldservice.platform.security.AccessScopeSpecificationContributor}
 * in its owning domain module.
 *
 * <p>Entities covered: {@code work_order}, {@code assignment}, {@code site},
 * {@code asset}, {@code stock_movement}. Non-scoped reference data (e.g. job
 * categories, certifications) must <em>not</em> implement this interface.
 */
public interface ScopedEntity {
}
