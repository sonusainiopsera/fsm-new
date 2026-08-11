package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;

import java.time.Instant;
import java.util.List;

/**
 * Mints a short-lived RS256 access token and a 256-bit SecureRandom refresh handle.
 *
 * <p>The access token is returned as a signed JWT string (not persisted).
 * The refresh handle is returned as a base64url-encoded opaque string; only its
 * SHA-256 hex digest must be persisted — the plaintext handle must never touch
 * the database.
 */
public interface TokenIssuer {

    /**
     * Issues a token bundle for the authenticated user.
     *
     * @param user  the authenticated user
     * @param roles their granted roles
     * @return the bundle containing the signed JWT and opaque refresh handle
     */
    TokenBundle issue(AppUser user, List<AppRole> roles);

    /**
     * Container for the two issued tokens plus the access token's expiry instant.
     *
     * @param accessToken    signed RS256 JWT; return in response body only
     * @param refreshHandle  256-bit SecureRandom opaque value, base64url-encoded;
     *                       put in HttpOnly cookie; store only SHA-256 hash in DB
     * @param accessTokenExp instant at which the access token expires
     */
    record TokenBundle(String accessToken, String refreshHandle, Instant accessTokenExp) {}
}
