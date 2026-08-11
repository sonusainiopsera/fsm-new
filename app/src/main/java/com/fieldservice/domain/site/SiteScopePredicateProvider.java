package com.fieldservice.domain.site;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link Site}.
 *
 * <p>Predicate logic:
 * <ul>
 *   <li><strong>DISPATCHER / ADMIN / MANAGER</strong> — permit-all.</li>
 *   <li><strong>TECHNICIAN</strong> — permit-all for sites; technicians need to read site
 *       details for their assigned work orders. The work order itself is already scoped.</li>
 *   <li><strong>CUSTOMER</strong> — {@code customer_account_id IN (:accountIds)};
 *       a customer sees only their own sites.</li>
 * </ul>
 */
@Component
public class SiteScopePredicateProvider implements ScopedEntityPredicateProvider<Site> {

    @Override
    public Class<Site> getEntityType() {
        return Site.class;
    }

    @Override
    public Specification<Site> forScope(AccessScope scope) {
        if (scope.isPrivileged() || scope.isTechnician()) {
            // Technicians can read site details for their assigned work orders
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) {
                return (root, query, cb) -> cb.disjunction();
            }
            return (root, query, cb) ->
                    root.get("customerAccountId").in(scope.customerAccountIds());
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity Site. Access denied.");
    }
}
