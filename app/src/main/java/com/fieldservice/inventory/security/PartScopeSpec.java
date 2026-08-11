package com.fieldservice.inventory.security;

import com.fieldservice.inventory.domain.Part;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.EntityScopeSpec;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Row-scope predicate for {@link Part}.
 *
 * <ul>
 *   <li>DISPATCHER / ADMIN / MANAGER / TECHNICIAN — permit all (parts are a global catalogue)</li>
 *   <li>CUSTOMER — deny all (no inventory visibility)</li>
 *   <li>none / unknown — deny all</li>
 * </ul>
 */
@Component
public class PartScopeSpec implements EntityScopeSpec<Part> {

    @Override
    public Class<Part> entityType() {
        return Part.class;
    }

    @Override
    public Specification<Part> specFor(AccessScope scope) {
        if (scope == null || scope.isEmpty()) {
            return denyAll();
        }
        if (scope.isCustomer()) {
            return denyAll();
        }
        if (scope.isPrivileged() || scope.isTechnician()) {
            return permitAll();
        }
        return denyAll();
    }

    private static Specification<Part> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private static Specification<Part> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
