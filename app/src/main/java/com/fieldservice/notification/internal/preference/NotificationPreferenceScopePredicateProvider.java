package com.fieldservice.notification.internal.preference;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedEntityPredicateProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Row-scope predicate for {@link NotificationPreferenceEntity}.
 *
 * <p>ADMIN — permit-all (manages preferences for any user).
 * All other roles — restricted to their own {@code user_id}.
 */
@Component
class NotificationPreferenceScopePredicateProvider
        implements ScopedEntityPredicateProvider<NotificationPreferenceEntity> {

    @Override
    public Class<NotificationPreferenceEntity> getEntityType() {
        return NotificationPreferenceEntity.class;
    }

    @Override
    public Specification<NotificationPreferenceEntity> forScope(AccessScope scope) {
        if (scope.hasRole("ADMIN")) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("userId"), scope.userId());
    }
}
