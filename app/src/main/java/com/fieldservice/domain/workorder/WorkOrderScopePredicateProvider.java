package com.fieldservice.domain.workorder;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link WorkOrder}.
 *
 * <p>Predicate logic:
 * <ul>
 *   <li><strong>DISPATCHER / ADMIN / MANAGER</strong> — permit-all.</li>
 *   <li><strong>TECHNICIAN</strong> — {@code assigned_technician_id = :technicianId}.
 *       A reassignment takes effect immediately on the next request.</li>
 *   <li><strong>CUSTOMER</strong> — {@code customer_id IN (:accountIds)}.
 *       Work orders have a direct {@code customer_id} FK, avoiding the need for a site join.
 *       A customer with no linked accounts receives deny-all (disjunction).</li>
 * </ul>
 */
@Component
public class WorkOrderScopePredicateProvider implements ScopedEntityPredicateProvider<WorkOrder> {

    @Override
    public Class<WorkOrder> getEntityType() {
        return WorkOrder.class;
    }

    @Override
    public Specification<WorkOrder> forScope(AccessScope scope) {
        if (scope.isPrivileged()) {
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                throw new ScopedAccessDeniedException(
                        "TECHNICIAN principal is missing technicianId in access scope");
            }
            return (root, query, cb) ->
                    cb.equal(root.get("assignedTechnicianId"), scope.technicianId());
        }

        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) {
                return (root, query, cb) -> cb.disjunction();
            }
            return (root, query, cb) ->
                    root.get("customerId").in(scope.customerAccountIds());
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity WorkOrder. Access denied.");
    }
}
