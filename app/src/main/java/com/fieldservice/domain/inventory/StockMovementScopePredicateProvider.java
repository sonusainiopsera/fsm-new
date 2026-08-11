package com.fieldservice.domain.inventory;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link StockMovement}.
 *
 * <p>TECHNICIAN sees only stock movements they performed.
 * CUSTOMER receives deny-all (inventory data is not customer-facing).
 */
@Component
public class StockMovementScopePredicateProvider implements ScopedEntityPredicateProvider<StockMovement> {

    @Override
    public Class<StockMovement> getEntityType() {
        return StockMovement.class;
    }

    @Override
    public Specification<StockMovement> forScope(AccessScope scope) {
        if (scope.isPrivileged()) {
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                throw new ScopedAccessDeniedException(
                        "TECHNICIAN principal is missing technicianId in access scope");
            }
            return (root, query, cb) ->
                    cb.equal(root.get("technicianId"), scope.technicianId());
        }

        if (scope.isCustomer()) {
            // Stock movements are not exposed in the customer portal — deny-all.
            return (root, query, cb) -> cb.disjunction();
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity StockMovement. Access denied.");
    }
}
