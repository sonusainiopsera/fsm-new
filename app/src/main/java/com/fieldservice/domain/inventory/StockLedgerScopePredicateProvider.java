package com.fieldservice.domain.inventory;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link StockLedger}.
 *
 * <p>Predicate logic:
 * <ul>
 *   <li><strong>DISPATCHER / ADMIN / MANAGER</strong> — permit-all.</li>
 *   <li><strong>TECHNICIAN</strong> — {@code technician_id = :technicianId};
 *       technicians see only ledger entries they generated.</li>
 *   <li><strong>CUSTOMER</strong> — deny-all; ledger data is internal.</li>
 * </ul>
 */
@Component
public class StockLedgerScopePredicateProvider implements ScopedEntityPredicateProvider<StockLedger> {

    @Override
    public Class<StockLedger> getEntityType() {
        return StockLedger.class;
    }

    @Override
    public Specification<StockLedger> forScope(AccessScope scope) {
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
            return (root, query, cb) -> cb.disjunction();
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity StockLedger. Access denied.");
    }
}
