package com.fieldservice.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Thrown when a principal attempts to access a resource outside their AccessScope.
 *
 * <p>Non-disclosure contract: for scoped entities, absent (nonexistent) and out-of-scope ids
 * are deliberately collapsed into the same 403 response so a cross-role probe cannot distinguish
 * a forbidden resource from a nonexistent one. This decision is documented here as the authoritative
 * reference; callers must not expose 404 for any request on a scoped entity type.
 *
 * <p>The resource identifier is hashed before being included in log records so the requested
 * identifier is never echoed to a log sink or error response.
 */
public class ScopedAccessDeniedException extends RuntimeException {

    private final String resourceType;
    private final String resourceIdHash;

    /**
     * Creates a scope denial with a diagnostic message only (e.g., for scope resolution failures).
     *
     * @param message internal diagnostic message — never sent to the client
     */
    public ScopedAccessDeniedException(String message) {
        super(message);
        this.resourceType = null;
        this.resourceIdHash = null;
    }

    /**
     * Creates a scope denial for a specific resource lookup.
     * The resource id is SHA-256-hashed so it can appear in log records without
     * echoing the requested identifier back to the client.
     *
     * @param resourceType  entity type name (e.g., {@code "WorkOrder"})
     * @param resourceId    the requested resource identifier (hashed before logging)
     */
    public ScopedAccessDeniedException(String resourceType, UUID resourceId) {
        super("Access denied to " + resourceType);
        this.resourceType = resourceType;
        this.resourceIdHash = hashId(resourceId);
    }

    /** Entity type name, present when the denial is for a specific resource. */
    public Optional<String> getResourceType() {
        return Optional.ofNullable(resourceType);
    }

    /**
     * SHA-256 hash prefix of the requested resource identifier, safe for log records.
     * Present when the denial is for a specific resource.
     */
    public Optional<String> getResourceIdHash() {
        return Optional.ofNullable(resourceIdHash);
    }

    private static String hashId(UUID id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(id.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8); // first 16 hex chars for log correlation
        } catch (NoSuchAlgorithmException e) {
            return "[redacted]";
        }
    }
}
