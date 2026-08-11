package com.fieldservice.domain.workorder;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.Role;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link WorkOrder}.
 *
 * <p>Predicate logic:
 * <ul>
 *   <li><strong>DISPATCHER / ADMIN / MANAGER</strong> — permit-all ({@code cb.conjunction()}).
 *       These privileged roles see all work orders.</li>
 *   <li><strong>TECHNICIAN</strong> — {@code assigned_technician_id = :technicianId}.
 *       The scope is derived from the {@code technicianId} JWT claim. A reassignment
 *       takes effect immediately on the next request without cache invalidation because
 *       the predicate evaluates the current database state.</li>
 *   <li><strong>CUSTOMER</strong> — {@code site.customer_account_id IN (:accountIds)}.
 *       Joins to the site table and filters by the union of customer account IDs
 *       from the {@code customerAccountIds} JWT claim. A customer with two linked accounts
 *       sees the union and nothing beyond it. A customer with no linked accounts sees
 *       nothing (disjunction).</li>
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
            return permitAll();
        }

        if (scope.isTechnician()) {
            return technicianScope(scope);
        }

        if (scope.isCustomer()) {
            return customerScope(scope);
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity WorkOrder. Access denied.");
    }

    /**
     * Permit-all predicate for privileged roles (DISPATCHER, ADMIN, MANAGER).
     * Produces no additional WHERE clause condition.
     */
    private static Specification<WorkOrder> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    /**
     * Technician scope: only work orders where assigned_technician_id = technicianId.
     *
     * <p>This evaluates against the current database value of assigned_technician_id,
     * so a reassignment takes effect immediately on the next request.
     */
    private static Specification<WorkOrder> technicianScope(AccessScope scope) {
        if (scope.technicianId() == null) {
            throw new ScopedAccessDeniedException(
                    "TECHNICIAN principal is missing technicianId in access scope");
        }
        return (root, query, cb) ->
                cb.equal(root.get("assignedTechnicianId"), scope.technicianId());
    }

    /**
     * Customer scope: work orders whose site.customer_account_id is in the principal's accounts.
     *
     * <p>Uses an INNER JOIN to site so work orders with a null or invalid siteId are excluded.
     * A customer with an empty account set receives a deny-all (disjunction).
     */
    private static Specification<WorkOrder> customerScope(AccessScope scope) {
        if (scope.customerAccountIds().isEmpty()) {
            // Customer with no linked accounts: deny all.
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            Join<WorkOrder, Site> site = root.join("site", JoinType.INNER);
            return site.get("customerAccountId").in(scope.customerAccountIds());
        };
    }
}
