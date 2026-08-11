package com.fieldservice.domain.asset;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Constructs JPA {@link Specification} row-scope predicates for {@link Asset}.
 *
 * <p>Assets are scoped via their owning site. A CUSTOMER sees only assets
 * at sites belonging to their customer accounts. The predicate joins to the
 * site table and filters by {@code site.customer_id}.
 */
@Component
public class AssetScopePredicateProvider implements ScopedEntityPredicateProvider<Asset> {

    @Override
    public Class<Asset> getEntityType() {
        return Asset.class;
    }

    @Override
    public Specification<Asset> forScope(AccessScope scope) {
        if (scope.isPrivileged() || scope.isTechnician()) {
            return (root, query, cb) -> cb.conjunction();
        }

        if (scope.isCustomer()) {
            if (scope.customerAccountIds().isEmpty()) {
                return (root, query, cb) -> cb.disjunction();
            }
            return (root, query, cb) -> {
                Join<Asset, Site> site = root.join("site", JoinType.INNER);
                return site.get("customerId").in(scope.customerAccountIds());
            };
        }

        throw new ScopedAccessDeniedException(
                "No scope predicate defined for roles: " + scope.roles() +
                " on entity Asset. Access denied.");
    }
}
