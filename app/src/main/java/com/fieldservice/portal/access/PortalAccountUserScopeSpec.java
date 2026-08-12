package com.fieldservice.portal.access;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.EntityScopeSpec;
import com.fieldservice.portal.domain.PortalAccountUser;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Row-scope predicate for {@link PortalAccountUser}.
 *
 * <p>Every principal (regardless of role) may only read the linkage row whose
 * {@code user_id} matches their own authenticated {@code userId}. This prevents one
 * customer from discovering another customer's account linkage.
 *
 * <p>If the scope is empty or the userId is null, a deny-all predicate is returned so
 * the system never fails open.
 */
@Component
public class PortalAccountUserScopeSpec implements EntityScopeSpec<PortalAccountUser> {

    @Override
    public Class<PortalAccountUser> entityType() {
        return PortalAccountUser.class;
    }

    @Override
    public Specification<PortalAccountUser> specFor(AccessScope scope) {
        if (scope == null || scope.userId() == null) {
            return denyAll();
        }
        java.util.UUID userId = scope.userId();
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    private static Specification<PortalAccountUser> denyAll() {
        return (root, query, cb) -> cb.disjunction();
    }
}
