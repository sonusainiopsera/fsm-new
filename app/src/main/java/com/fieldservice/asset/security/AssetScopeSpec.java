package com.fieldservice.asset.security;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.EntityScopeSpec;
import com.fieldservice.site.domain.Site;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Row-scope predicate factory for {@link Asset}.
 *
 * <ul>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit all</li>
 *   <li>TECHNICIAN — permit all (technicians need to see all assets at sites they visit)</li>
 *   <li>CUSTOMER — {@code siteId IN (SELECT id FROM site WHERE customerId IN :customerAccountIds)}</li>
 *   <li>none / unknown — deny all</li>
 * </ul>
 */
@Component
public class AssetScopeSpec implements EntityScopeSpec<Asset> {

    @Override
    public Class<Asset> entityType() {
        return Asset.class;
    }

    @Override
    public Specification<Asset> specFor(AccessScope scope) {
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
            return (root, query, cb) -> {
                var siteSubquery = query.subquery(UUID.class);
                var siteRoot = siteSubquery.from(Site.class);
                siteSubquery.select(siteRoot.get("id"))
                            .where(siteRoot.get("customerId").in(accountIds));
                return root.get("siteId").in(siteSubquery);
            };
        }

        return denyAll();
    }

    private static Specification<Asset> permitAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private static Specification<Asset> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
