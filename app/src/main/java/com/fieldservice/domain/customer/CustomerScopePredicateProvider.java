package com.fieldservice.domain.customer;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link Customer}.
 *
 * <p>Predicate logic:
 * <ul>
 *   <li><strong>DISPATCHER / ADMIN / MANAGER</strong> — permit-all.</li>
 *   <li><strong>TECHNICIAN</strong> — permit-all; technicians need customer names for work orders.</li>
 *   <li><strong>CUSTOMER</strong> — {@code id IN (:accountIds)}; a customer sees only
 *       their own customer record(s). The scope boundary matches the JWT
 *       {@code customerAccountIds} claim, which stores {@code customer.id} values.</li>
 * </ul>
 */
@Component
public class CustomerScopePredicateProvider implements ScopedEntityPredicateProvider<Customer> {

    @Override
    public Class<Customer> getEntityType() {
        return Customer.class;
    }

    @Override
    public Specification<Customer> forScope(AccessScope scope) {
        if (scope.isPrivileged() || scope.isTechnician()) {
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) {
                return (root, query, cb) -> cb.disjunction();
            }
            return (root, query, cb) ->
                    root.get("id").in(scope.customerAccountIds());
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity Customer. Access denied.");
    }
}
