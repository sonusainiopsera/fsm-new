package com.fieldservice.domain.scope;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.stockmovement.StockMovement;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.platform.security.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import jakarta.persistence.criteria.JoinType;

/**
 * Registers {@link Specification} builders for every scoped entity in the domain module.
 *
 * <h3>Role semantics</h3>
 * <dl>
 *   <dt>ADMIN / DISPATCHER / MANAGER</dt>
 *   <dd>Permit-all — see every row in the tenant.</dd>
 *   <dt>TECHNICIAN</dt>
 *   <dd>Work orders where {@code assigned_technician_id} equals their technician id.
 *       Assignments where {@code technician_id} equals their technician id.
 *       Sites and assets: permit-all (technicians need site/asset context for their jobs).
 *       Stock movements scoped to their own technician id.</dd>
 *   <dt>CUSTOMER</dt>
 *   <dd>Work orders, assignments, assets: scoped via site → {@code customer_account_id}
 *       IN the caller's linked account set.
 *       Sites: directly on {@code customer_account_id}.
 *       Stock movements: scoped via work order → site → customer account.</dd>
 * </dl>
 */
@Component
public class DomainScopeContributor implements AccessScopeSpecificationContributor {

    @Override
    public void contribute(AccessScopePredicateFactory factory) {
        factory.register(WorkOrder.class, this::workOrderSpec);
        factory.register(Assignment.class, this::assignmentSpec);
        factory.register(Site.class, this::siteSpec);
        factory.register(Asset.class, this::assetSpec);
        factory.register(StockMovement.class, this::stockMovementSpec);
    }

    // ── WorkOrder ──────────────────────────────────────────────────────────

    private Specification<WorkOrder> workOrderSpec(AccessScope scope) {
        if (scope.isPermitAll()) {
            return permitAll();
        }
        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                throw new ScopedAccessDeniedException(
                        "TECHNICIAN scope missing technician_id claim");
            }
            String tid = scope.technicianId();
            return (root, q, cb) -> cb.equal(root.get("assignedTechnicianId"), tid);
        }
        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) {
                return denyAll(); // no accounts linked — see nothing
            }
            var accountIds = scope.customerAccountIds();
            return (root, q, cb) -> {
                // Use join (not fetch) so count query works correctly
                var siteJoin = root.join("site", JoinType.INNER);
                return siteJoin.get("customerAccountId").in(accountIds);
            };
        }
        return denyAll();
    }

    // ── Assignment ─────────────────────────────────────────────────────────

    private Specification<Assignment> assignmentSpec(AccessScope scope) {
        if (scope.isPermitAll()) return permitAll();
        if (scope.isTechnician()) {
            String tid = scope.technicianId();
            if (tid == null) throw new ScopedAccessDeniedException("TECHNICIAN missing technician_id");
            return (root, q, cb) -> cb.equal(root.get("technicianId"), tid);
        }
        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) return denyAll();
            var accountIds = scope.customerAccountIds();
            return (root, q, cb) -> {
                var woJoin = root.join("workOrder", JoinType.INNER);
                var siteJoin = woJoin.join("site", JoinType.INNER);
                return siteJoin.get("customerAccountId").in(accountIds);
            };
        }
        return denyAll();
    }

    // ── Site ───────────────────────────────────────────────────────────────

    private Specification<Site> siteSpec(AccessScope scope) {
        if (scope.isPermitAll() || scope.isTechnician()) return permitAll();
        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) return denyAll();
            var accountIds = scope.customerAccountIds();
            return (root, q, cb) -> root.get("customerAccountId").in(accountIds);
        }
        return denyAll();
    }

    // ── Asset ──────────────────────────────────────────────────────────────

    private Specification<Asset> assetSpec(AccessScope scope) {
        if (scope.isPermitAll() || scope.isTechnician()) return permitAll();
        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) return denyAll();
            var accountIds = scope.customerAccountIds();
            return (root, q, cb) -> {
                var siteJoin = root.join("site", JoinType.INNER);
                return siteJoin.get("customerAccountId").in(accountIds);
            };
        }
        return denyAll();
    }

    // ── StockMovement ──────────────────────────────────────────────────────

    private Specification<StockMovement> stockMovementSpec(AccessScope scope) {
        if (scope.isPermitAll()) return permitAll();
        if (scope.isTechnician()) {
            String tid = scope.technicianId();
            if (tid == null) throw new ScopedAccessDeniedException("TECHNICIAN missing technician_id");
            return (root, q, cb) -> cb.equal(root.get("technicianId"), tid);
        }
        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) return denyAll();
            var accountIds = scope.customerAccountIds();
            return (root, q, cb) -> {
                var woJoin = root.join("workOrder", JoinType.INNER);
                var siteJoin = woJoin.join("site", JoinType.INNER);
                return siteJoin.get("customerAccountId").in(accountIds);
            };
        }
        return denyAll();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static <T> Specification<T> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private static <T> Specification<T> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
