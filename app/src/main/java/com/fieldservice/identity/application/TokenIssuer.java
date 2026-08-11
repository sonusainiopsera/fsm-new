package com.fieldservice.identity.application;

import java.util.List;
import java.util.UUID;

/**
 * Issues short-lived RS256 access tokens for authenticated principals.
 */
public interface TokenIssuer {

    /**
     * Mints a signed JWT with claims: sub, roles, jti, iat, exp (15 min), iss, aud.
     *
     * @param userId      subject identifier (UUID)
     * @param email       canonical email (for logging only; not placed in the token)
     * @param roles       role names to embed in the {@code roles} claim
     * @return compact serialised JWT
     */
    String issueAccessToken(UUID userId, String email, List<String> roles);
}
