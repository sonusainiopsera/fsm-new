package com.fieldservice.platform.security;

import com.fieldservice.platform.persistence.ScopedEntity;
import org.springframework.data.jpa.domain.Specification;

/**
 * Strategy interface for constructing a JPA {@link Specification} row-scope predicate
 * for a specific scoped entity type.
 *
 * <p>One implementation must be registered as a Spring bean per entity type that implements
 * {@link ScopedEntity}. The {@link AccessScopePredicateFactory} collects all implementations
 * at startup and validates completeness.
 *
 * <p>Predicate semantics per role:
 * <table>
 *   <tr><th>Role</th><th>Predicate</th></tr>
 *   <tr><td>DISPATCHER / ADMIN / MANAGER</td><td>permit-all ({@code cb.conjunction()})</td></tr>
 *   <tr><td>TECHNICIAN</td><td>entity-specific; typically assigned_technician_id = technicianId</td></tr>
 *   <tr><td>CUSTOMER</td><td>entity-specific; typically site.customer_account_id IN (accountIds)</td></tr>
 * </table>
 *
 * @param <T> the entity type this provider serves; must implement {@link ScopedEntity}
 */
public interface ScopedEntityPredicateProvider<T extends ScopedEntity> {

    /**
     * Returns the entity class this provider handles.
     *
     * @return the entity {@link Class}, never {@code null}
     */
    Class<T> getEntityType();

    /**
     * Constructs a {@link Specification} predicate that restricts query results to rows
     * the given {@code scope} is authorised to see.
     *
     * <p>Implementations must never return {@code null}. Use {@code cb.conjunction()} for
     * permit-all and {@code cb.disjunction()} for deny-all (e.g., customer with no linked accounts).
     *
     * @param scope the resolved access scope for the current request
     * @return a non-null {@link Specification}
     * @throws ScopedAccessDeniedException if the scope is structurally invalid for this entity
     *         (e.g., TECHNICIAN principal with no technicianId)
     */
    Specification<T> forScope(AccessScope scope);
}
