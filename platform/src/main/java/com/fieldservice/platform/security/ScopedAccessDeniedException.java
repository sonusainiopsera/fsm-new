package com.fieldservice.platform.security;

/**
 * Thrown when a principal attempts to access a resource that is outside their row scope,
 * or when scope resolution itself fails (unauthenticated, malformed JWT, etc.).
 *
 * <p><strong>Non-disclosure contract</strong> (BR-19 / OWASP A01): this exception is
 * intentionally used for <em>both</em> "resource does not exist" and "resource exists but
 * is out of scope" cases on scoped entity reads. Callers must map it to a uniform HTTP 403
 * response with no existence information in the body, headers, or response length. This
 * decision is documented here and in {@code com.fieldservice.platform.api} package notes.
 *
 * <p>Structured denial logging (actor, role, resource type, resource-id hash, traceId) is
 * performed by the global exception handler — this class intentionally carries no resource
 * identity fields to avoid them being logged accidentally before the hash step.
 */
public class ScopedAccessDeniedException extends RuntimeException {

    private final String resourceType;

    /** Use when scope resolution itself fails (no resource context). */
    public ScopedAccessDeniedException(String message) {
        super(message);
        this.resourceType = null;
    }

    /**
     * Use when a specific resource fetch is denied.
     *
     * @param resourceType the entity type name (e.g. "work_order") — safe to log
     * @param message      internal message — never echoed to the client
     */
    public ScopedAccessDeniedException(String resourceType, String message) {
        super(message);
        this.resourceType = resourceType;
    }

    /**
     * Returns the entity type associated with the denial, if available.
     * Safe to include in structured log entries.
     */
    public String resourceType() {
        return resourceType;
    }
}
