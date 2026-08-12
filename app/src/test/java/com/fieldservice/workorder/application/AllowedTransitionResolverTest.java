package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AllowedTransitionResolver} — no Spring context.
 *
 * <p>Covers:
 * <ul>
 *   <li>TECHNICIAN in ASSIGNED sees DEPART, START but not CANCEL</li>
 *   <li>DISPATCHER in NEW sees ASSIGN and CANCEL</li>
 *   <li>TECHNICIAN in IN_PROGRESS sees START (already in), HOLD and COMPLETE but not CANCEL</li>
 *   <li>Terminal state (CLOSED) returns empty set</li>
 *   <li>Null authentication returns empty set</li>
 *   <li>DISPATCHER sees all events allowed for their role</li>
 * </ul>
 */
class AllowedTransitionResolverTest {

    private final AllowedTransitionResolver resolver = new AllowedTransitionResolver();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void technician_inAssigned_seesDepartAndStart_notCancel() {
        setAuth(Role.TECHNICIAN);

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.ASSIGNED);

        assertThat(allowed).contains("DEPART", "START");
        assertThat(allowed).doesNotContain("CANCEL", "ASSIGN");
    }

    @Test
    void dispatcher_inNew_seesAssignAndCancel() {
        setAuth(Role.DISPATCHER);

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.NEW);

        assertThat(allowed).contains("ASSIGN", "CANCEL");
    }

    @Test
    void technician_inInProgress_seesHoldAndComplete_notCancel() {
        setAuth(Role.TECHNICIAN);

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.IN_PROGRESS);

        assertThat(allowed).contains("HOLD", "COMPLETE");
        assertThat(allowed).doesNotContain("CANCEL");
    }

    @Test
    void closedState_returnsEmptySet() {
        setAuth(Role.DISPATCHER);

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.CLOSED);

        assertThat(allowed).isEmpty();
    }

    @Test
    void cancelledState_returnsEmptySet() {
        setAuth(Role.TECHNICIAN);

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.CANCELLED);

        assertThat(allowed).isEmpty();
    }

    @Test
    void noAuthentication_returnsEmptySet() {
        SecurityContextHolder.clearContext(); // explicit — no auth set

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.ASSIGNED);

        assertThat(allowed).isEmpty();
    }

    @Test
    void dispatcher_inAssigned_seesAllDispatcherTransitionsIncludingCancel() {
        setAuth(Role.DISPATCHER);

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.ASSIGNED);

        assertThat(allowed).contains("DEPART", "START", "CANCEL");
    }

    @Test
    void technician_inOnHold_seesResume() {
        setAuth(Role.TECHNICIAN);

        Set<String> allowed = resolver.resolveForCaller(WorkOrderState.ON_HOLD);

        assertThat(allowed).contains("RESUME");
        assertThat(allowed).doesNotContain("CANCEL");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void setAuth(String roleWithPrefix) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(
                        "user", "cred",
                        new SimpleGrantedAuthority(roleWithPrefix)
                )
        );
    }
}
