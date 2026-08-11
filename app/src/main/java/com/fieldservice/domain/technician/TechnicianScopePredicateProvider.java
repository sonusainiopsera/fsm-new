package com.fieldservice.domain.technician;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link Technician}.
 *
 * <p>Predicate logic:
 * <ul>
 *   <li><strong>DISPATCHER / ADMIN / MANAGER</strong> — permit-all.</li>
 *   <li><strong>TECHNICIAN</strong> — {@code id = :technicianId}; a technician sees
 *       only their own profile.</li>
 *   <li><strong>CUSTOMER</strong> — deny-all; technician profiles are not customer-facing.</li>
 * </ul>
 */
@Component
public class TechnicianScopePredicateProvider implements ScopedEntityPredicateProvider<Technician> {

    @Override
    public Class<Technician> getEntityType() {
        return Technician.class;
    }

    @Override
    public Specification<Technician> forScope(AccessScope scope) {
        if (scope.isPrivileged()) {
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                throw new ScopedAccessDeniedException(
                        "TECHNICIAN principal is missing technicianId in access scope");
            }
            return (root, query, cb) ->
                    cb.equal(root.get("id"), scope.technicianId());
        }

        if (scope.isCustomer()) {
            return (root, query, cb) -> cb.disjunction();
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity Technician. Access denied.");
    }
}
