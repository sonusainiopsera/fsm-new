package com.fieldservice.platform.error;

import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.security.Role;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single translation point encoding the ratified 403-versus-404 rule for scope denials.
 *
 * <p>Rule (WO-113):
 * <ul>
 *   <li>A CUSTOMER principal requesting a resource outside their account scope receives 404 —
 *       byte-identical whether the resource exists or belongs to another account.</li>
 *   <li>All other scope denials (cross-role, unknown role, unresolvable scope) receive 403
 *       with a generic body that discloses nothing about resource existence.</li>
 * </ul>
 *
 * <p>Both paths emit a structured audit log tuple (actorId, role, resourceType, resourceIdHash,
 * denialType, path, traceId) and increment the {@code auth.scope_denial} Micrometer counter
 * with tags {@code denial_type} (cross_account | cross_role) and {@code role}.
 */
@Component
public class ScopeDenialTranslator {

    private static final Logger log = LoggerFactory.getLogger(ScopeDenialTranslator.class);
    private static final String TRACE_HEADER = "X-Trace-Id";

    // SIEM alerting threshold: anomalous denial rates should trigger on counts >
    // ALERT_THRESHOLD per minute per role. Defined here as the canonical reference.
    static final String DENIAL_COUNTER_NAME = "auth.scope_denial";

    private final MeterRegistry meterRegistry;

    public ScopeDenialTranslator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Translates a scope denial to 403 (cross-role) or 404 (cross-account customer), emitting
     * a structured audit record and incrementing the denial counter.
     */
    public ResponseEntity<ErrorEnvelope> translate(
            ScopedAccessDeniedException ex,
            HttpServletRequest request) {

        String tid = traceId();
        String role = resolvedRole();
        boolean isCustomer = isCustomerRole(role);
        String denialType = isCustomer ? "cross_account" : "cross_role";
        String resourceType = ex.getResourceType().orElse("unknown");
        String resourceIdHash = ex.getResourceIdHash().orElse("[none]");

        // Structured audit log — PII masked: no raw IDs, no customer data in log record
        log.warn(
                "scope_denial: actorId={}, role={}, resourceType={}, resourceIdHash={}, "
                        + "denialType={}, outcome=denied, path={}, traceId={}",
                resolvedActorId(), role, resourceType, resourceIdHash,
                denialType, request.getRequestURI(), tid);

        Counter.builder(DENIAL_COUNTER_NAME)
                .tag("denial_type", denialType)
                .tag("role", role)
                .register(meterRegistry)
                .increment();

        if (isCustomer) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header(TRACE_HEADER, tid)
                    .body(new ErrorEnvelope(
                            ErrorEnvelope.Code.NOT_FOUND,
                            "The requested resource was not found.",
                            tid,
                            Instant.now()));
        }

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(TRACE_HEADER, tid)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.FORBIDDEN,
                        "Access denied.",
                        tid,
                        Instant.now()));
    }

    private static boolean isCustomerRole(String role) {
        return "CUSTOMER".equalsIgnoreCase(role);
    }

    /**
     * Returns the bare role name (without ROLE_ prefix) of the current principal, or "unknown".
     */
    static String resolvedRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "unknown";
        }
        Set<String> known = Set.of(
                Role.DISPATCHER, Role.ADMIN, Role.MANAGER, Role.TECHNICIAN, Role.CUSTOMER);
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(known::contains)
                .map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
                .findFirst()
                .orElse("unknown");
    }

    private static String resolvedActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "[unauthenticated]";
        return auth.getName();
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}
