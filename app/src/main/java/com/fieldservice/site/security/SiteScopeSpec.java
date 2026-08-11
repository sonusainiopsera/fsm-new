package com.fieldservice.site.security;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.EntityScopeSpec;
import com.fieldservice.site.domain.Site;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Row-scope predicate factory for {@link Site}.
 *
 * <ul>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit all</li>
 *   <li>TECHNICIAN — permit all (technicians can see sites they're dispatched to)</li>
 *   <li>CUSTOMER — {@code customer_account_id IN :customerAccountIds}</li>
 *   <li>none / unknown — deny all</li>
 * </ul>
 */
@Component
public class SiteScopeSpec implements EntityScopeSpec<Site> {

    @Override
    public Class<Site> entityType() {
        return Site.class;
    }

    @Override
    public Specification<Site> specFor(AccessScope scope) {
        if (scope == null || scope.isEmpty()) {
            return denyAll();
        }

        if (scope.isPrivileged() || scope.isTechnician()) {
            return permitAll();
        }

        if (scope.isCustomer()) {
            if (scope.customerAccountIds() == null || scope.customerAccountIds().isEmpty()) {
                return denyAll();
            }
            var accountIds = scope.customerAccountIds();
            return (root, query, cb) -> root.get("customerId").in(accountIds);
        }

        return denyAll();
    }

    private static Specification<Site> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private static Specification<Site> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
