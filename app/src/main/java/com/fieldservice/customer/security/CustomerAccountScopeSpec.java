package com.fieldservice.customer.security;

import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.EntityScopeSpec;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Row-scope predicate factory for {@link CustomerAccount}.
 *
 * <ul>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit all</li>
 *   <li>TECHNICIAN — permit all (technicians need to see customer info for their jobs)</li>
 *   <li>CUSTOMER — {@code id IN :customerAccountIds}</li>
 *   <li>none / unknown — deny all</li>
 * </ul>
 */
@Component
public class CustomerAccountScopeSpec implements EntityScopeSpec<CustomerAccount> {

    @Override
    public Class<CustomerAccount> entityType() {
        return CustomerAccount.class;
    }

    @Override
    public Specification<CustomerAccount> specFor(AccessScope scope) {
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
            return (root, query, cb) -> root.get("id").in(accountIds);
        }

        return denyAll();
    }

    private static Specification<CustomerAccount> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private static Specification<CustomerAccount> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
