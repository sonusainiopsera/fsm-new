package com.fieldservice.domain.assignment;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link Assignment}.
 *
 * <p>TECHNICIAN sees only assignments for their own technician id.
 * CUSTOMER receives deny-all (assignments are internal operational data,
 * not exposed in the customer portal).
 */
@Component
public class AssignmentScopePredicateProvider implements ScopedEntityPredicateProvider<Assignment> {

    @Override
    public Class<Assignment> getEntityType() {
        return Assignment.class;
    }

    @Override
    public Specification<Assignment> forScope(AccessScope scope) {
        if (scope.isPrivileged()) {
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                throw new ScopedAccessDeniedException(
                        "TECHNICIAN principal is missing technicianId in access scope");
            }
            return (root, query, cb) ->
                    cb.equal(root.get("technicianId"), scope.technicianId());
        }

        if (scope.isCustomer()) {
            // Assignments are not exposed in the customer portal — deny-all.
            return (root, query, cb) -> cb.disjunction();
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity Assignment. Access denied.");
    }
}
