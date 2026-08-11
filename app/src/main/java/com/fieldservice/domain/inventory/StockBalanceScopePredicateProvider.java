package com.fieldservice.domain.inventory;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link StockBalance}.
 *
 * <p>Predicate logic:
 * <ul>
 *   <li><strong>DISPATCHER / ADMIN / MANAGER</strong> — permit-all.</li>
 *   <li><strong>TECHNICIAN</strong> — {@code location_id IN (SELECT id FROM stock_location WHERE technician_id = :technicianId)};
 *       technicians see only balances in their own van location.</li>
 *   <li><strong>CUSTOMER</strong> — deny-all; inventory data is not customer-facing.</li>
 * </ul>
 */
@Component
public class StockBalanceScopePredicateProvider implements ScopedEntityPredicateProvider<StockBalance> {

    @Override
    public Class<StockBalance> getEntityType() {
        return StockBalance.class;
    }

    @Override
    public Specification<StockBalance> forScope(AccessScope scope) {
        if (scope.isPrivileged()) {
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                throw new ScopedAccessDeniedException(
                        "TECHNICIAN principal is missing technicianId in access scope");
            }
            return (root, query, cb) -> {
                Subquery<UUID> locationSub = query.subquery(UUID.class);
                Root<StockLocation> loc = locationSub.from(StockLocation.class);
                locationSub.select(loc.get("id"))
                        .where(cb.equal(loc.get("technicianId"), scope.technicianId()));
                return root.get("locationId").in(locationSub);
            };
        }

        if (scope.isCustomer()) {
            return (root, query, cb) -> cb.disjunction();
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity StockBalance. Access denied.");
    }
}
