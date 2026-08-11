package com.fieldservice.identity.token;

import java.time.Instant;
import java.util.List;

/**
 * Immutable payload stored in Redis for a single-use SSE stream ticket.
 *
 * <p>All fields are serialized as a Redis hash. The ticket's Redis key is a SHA-256
 * hash of the opaque ticket value, so the plaintext value is never persisted.
 *
 * @param userId        authenticated user identifier (UUID as string)
 * @param authorities   Spring Security authority strings (e.g. {@code "ROLE_TECHNICIAN"})
 * @param clientIp      IP address of the issuing request (trusted-proxy resolved)
 * @param issuedAt      UTC instant of issuance
 * @param jti           JWT ID of the originating access token, checked against denylist on redemption
 */
public record StreamTicketPayload(
        String userId,
        List<String> authorities,
        String clientIp,
        Instant issuedAt,
        String jti
) {}
