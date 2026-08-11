package com.fieldservice.identity.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Successful authentication response.
 *
 * <p>The access token is returned only in the response body — never in a cookie
 * or persisted server-side. The refresh handle is delivered exclusively via an
 * HttpOnly Secure SameSite Strict cookie set by the controller.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        int    expiresIn,
        User   user
) {

    public static LoginResponse of(String accessToken, int expiresInSeconds, User user) {
        return new LoginResponse(accessToken, "Bearer", expiresInSeconds, user);
    }

    /**
     * Minimal user summary embedded in the login response for immediate client use.
     * Roles are the resolved granted authorities; an empty list is never returned
     * (grantless users are rejected before a token is issued).
     */
    public record User(UUID id, String displayName, List<String> roles) {}
}
