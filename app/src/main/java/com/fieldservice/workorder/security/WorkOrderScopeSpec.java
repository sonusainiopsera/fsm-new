package com.fieldservice.workorder.security;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.EntityScopeSpec;
import com.fieldservice.workorder.domain.WorkOrder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Row-scope predicate factory for {@link WorkOrder}.
 *
 * <h3>Predicate rules</h3>
 * <table border="1">
 *   <tr><th>Role</th><th>SQL predicate</th></tr>
 *   <tr><td>DISPATCHER</td><td>1=1 (permit all)</td></tr>
 *   <tr><td>ADMIN</td><td>1=1 (permit all)</td></tr>
 *   <tr><td>MANAGER</td><td>1=1 (permit all — read-only team-wide)</td></tr>
 *   <tr><td>TECHNICIAN</td><td>{@code assigned_technician_id = :technicianId}</td></tr>
 *   <tr><td>CUSTOMER</td><td>{@code site.customer_account_id IN :customerAccountIds}</td></tr>
 *   <tr><td>none / unknown</td><td>1=0 (deny all)</td></tr>
 * </table>
 *
 * <p>For CUSTOMER the predicate performs an INNER JOIN to the {@code site} table so only
 * work orders whose site belongs to one of the customer's accounts are returned.
 *
 * <p>All deny cases (missing technicianId, empty customerAccountIds, no roles) return a
 * deny-all predicate ({@code cb.disjunction()}) rather than throwing, so the scoped count
 * query and the page query both return zero rather than erroring.
 */
@Component
public class WorkOrderScopeSpec implements EntityScopeSpec<WorkOrder> {

    @Override
    public Class<WorkOrder> entityType() {
        return WorkOrder.class;
    }

    @Override
    public Specification<WorkOrder> specFor(AccessScope scope) {
        if (scope == null || scope.isEmpty()) {
            return denyAll();
        }

        if (scope.isPrivileged()) {
            return permitAll();
        }

        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                // TECHNICIAN role without technician_id claim → deny
                return denyAll();
            }
            java.util.UUID techId = scope.technicianId();
            return (root, query, cb) ->
                    cb.equal(root.get("assignedTechnicianId"), techId);
        }

        if (scope.isCustomer()) {
            if (scope.customerAccountIds() == null || scope.customerAccountIds().isEmpty()) {
                // CUSTOMER without any linked accounts → deny
                return denyAll();
            }
            var accountIds = scope.customerAccountIds();
            return (root, query, cb) -> {
                Join<Object, Object> siteJoin = root.join("site", JoinType.INNER);
                return siteJoin.get("customerId").in(accountIds);
            };
        }

        // No recognised role → deny all
        return denyAll();
    }

    // -------------------------------------------------------------------------
    // Predicate helpers
    // -------------------------------------------------------------------------

    private static Specification<WorkOrder> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private static Specification<WorkOrder> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
