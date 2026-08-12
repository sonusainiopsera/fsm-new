package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionTable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes the set of {@link WorkOrderEvent} names the authenticated caller may trigger
 * from a given {@link WorkOrderState}, based on the static transition table and the
 * caller's granted authorities.
 *
 * <p>Used to populate {@code allowedTransitions} in technician detail responses so the
 * client action bar is always derived from the server's authoritative transition rules
 * (WO-156 AC-1 / AC-3).
 *
 * <p>This resolver is intentionally read-only and stateless — it consults
 * {@link WorkOrderTransitionTable#legalEventsFrom} then filters by role. It does not
 * evaluate guards, which are only evaluated at mutation time.
 */
@Component
public class AllowedTransitionResolver {

    /**
     * Returns the event names the current caller may trigger from {@code state}.
     *
     * @param state the current work order state
     * @return unmodifiable set of event name strings (e.g. {@code "DEPART"})
     */
    public Set<String> resolveForCaller(WorkOrderState state) {
        Set<String> callerAuthorities = callerAuthorities();
        return WorkOrderTransitionTable.legalEventsFrom(state).stream()
                .filter(event -> {
                    var desc = WorkOrderTransitionTable.resolve(state, event);
                    return desc.isPresent()
                            && desc.get().requiredRoles().stream().anyMatch(callerAuthorities::contains);
                })
                .map(WorkOrderEvent::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> callerAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities == null) {
            return Set.of();
        }
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
