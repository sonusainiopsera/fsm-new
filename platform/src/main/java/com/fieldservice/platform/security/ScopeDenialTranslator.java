package com.fieldservice.platform.security;

import com.fieldservice.platform.api.exception.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Encodes the single, documented rule for translating a row-scope denial into an HTTP status.
 *
 * <h3>Ratified rule</h3>
 * <ul>
 *   <li><strong>403 FORBIDDEN</strong> — the principal has a recognised role but is denied
 *       by an in-tenant constraint (e.g. a TECHNICIAN probing another technician's work
 *       order). The response body discloses nothing about resource existence.</li>
 *   <li><strong>404 NOT_FOUND</strong> — the principal is a CUSTOMER probing a resource
 *       outside their account scope. The response is byte-identical whether the resource
 *       exists or not, so clients cannot infer existence from the status code.</li>
 * </ul>
 *
 * <p>Both cases are audited internally: a structured log line is emitted with the
 * traceId, actorId, role, resourceType, masked resourceId, and outcome, and a tagged
 * Micrometer counter ({@code security.scope.denial}) is incremented. Operations therefore
 * retain full visibility even though the client receives a non-disclosing response.
 *
 * <p>This class is the <em>only</em> place the 403-versus-404 decision is made. Every
 * controller that serves scoped entities must call {@link #deny} rather than throwing
 * the exceptions directly, so the rule is applied consistently and cannot drift.
 */
@Component
public class ScopeDenialTranslator {

    private static final Logger log = LoggerFactory.getLogger(ScopeDenialTranslator.class);

    static final String COUNTER_NAME = "security.scope.denial";

    private final MeterRegistry meterRegistry;

    public ScopeDenialTranslator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Emits a structured audit record, increments the denial counter, and throws the
     * appropriate exception based on the principal's role.
     *
     * <p>This method always throws — the return type is {@code void} only for readability.
     *
     * @param scope        the principal's resolved access scope
     * @param resourceType the entity type name, safe to log (e.g. {@code "work_order"})
     * @param resourceId   the requested resource identifier; masked in log output
     * @throws NotFoundException          if the principal's role is CUSTOMER (→ HTTP 404)
     * @throws ScopedAccessDeniedException for all other authenticated roles (→ HTTP 403)
     */
    public void deny(AccessScope scope, String resourceType, UUID resourceId) {
        String traceId  = resolveTraceId();
        String actorId  = scope.userId() != null ? scope.userId().toString() : "anonymous";
        String role     = scope.roles().stream().findFirst().orElse("NONE");
        String maskedId = maskId(resourceId);
        String outcome  = scope.isCustomer() ? "NOT_FOUND" : "FORBIDDEN";

        log.warn("scope_denial trace_id={} actor_id={} role={} resource={} masked_resource_id={} outcome={}",
                traceId, actorId, role, resourceType, maskedId, outcome);

        meterRegistry.counter(COUNTER_NAME,
                "role",     role,
                "resource", resourceType != null ? resourceType : "unknown",
                "outcome",  outcome)
                .increment();

        if (scope.isCustomer()) {
            throw new NotFoundException(resourceType, "scope-denied");
        }
        throw new ScopedAccessDeniedException(resourceType, "scope-denied");
    }

    /**
     * Returns the first 8 hex characters of the SHA-256 hash of the resource id.
     * This is sufficient for log correlation but too short to reconstruct the original value.
     */
    private static String maskId(UUID resourceId) {
        if (resourceId == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(resourceId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "????????";
        }
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
