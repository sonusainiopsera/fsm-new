package com.fieldservice.inventory.security;

import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.EntityScopeSpec;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Row-scope predicate for {@link StockLocation}.
 *
 * <ul>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit all</li>
 *   <li>TECHNICIAN — only the vehicle location where {@code technician_id = scope.technicianId()}</li>
 *   <li>CUSTOMER — deny all</li>
 *   <li>none / unknown — deny all</li>
 * </ul>
 */
@Component
public class StockLocationScopeSpec implements EntityScopeSpec<StockLocation> {

    @Override
    public Class<StockLocation> entityType() {
        return StockLocation.class;
    }

    @Override
    public Specification<StockLocation> specFor(AccessScope scope) {
        if (scope == null || scope.isEmpty()) {
            return denyAll();
        }
        if (scope.isCustomer()) {
            return denyAll();
        }
        if (scope.isPrivileged()) {
            return permitAll();
        }
        if (scope.isTechnician()) {
            UUID techId = scope.technicianId();
            if (techId == null) {
                return denyAll();
            }
            return (root, query, cb) -> cb.equal(root.get("technicianId"), techId);
        }
        return denyAll();
    }

    private static Specification<StockLocation> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private static Specification<StockLocation> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
