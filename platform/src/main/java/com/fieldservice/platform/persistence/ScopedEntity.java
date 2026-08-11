package com.fieldservice.platform.persistence;

/**
 * Marker interface for JPA entities that require row-level access scope enforcement.
 *
 * <p>All entities that implement this interface must have a corresponding
 * {@link com.fieldservice.platform.security.ScopedEntityPredicateProvider} registered
 * as a Spring bean. The {@link com.fieldservice.platform.security.AccessScopePredicateFactory}
 * validates this at application startup and fails fast if any scoped entity type is missing
 * a predicate provider — ensuring gaps cannot reach production.
 *
 * <p>Entities covered:
 * <ul>
 *   <li>{@code WorkOrder} — primary scoped entity; technician and customer scope</li>
 *   <li>{@code Assignment} — scoped by technician</li>
 *   <li>{@code Site} — scoped by customer account</li>
 *   <li>{@code Asset} — scoped via site → customer account</li>
 *   <li>{@code StockMovement} — scoped by technician</li>
 * </ul>
 *
 * @see com.fieldservice.platform.security.AccessScopePredicateFactory
 * @see com.fieldservice.platform.security.ScopedEntityPredicateProvider
 */
public interface ScopedEntity {
    // Marker interface — no methods required.
    // Implemented by all entities that carry mandatory row-scope predicates.
}
