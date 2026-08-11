package com.fieldservice.identity.api.dto;

/**
 * Successful refresh-rotation response body.
 *
 * <p>The rotated refresh handle is delivered exclusively via an HttpOnly Secure
 * SameSite=Strict cookie set by the controller, not in this body.
 */
public record RefreshResponse(
        String accessToken,
        String tokenType,
        int    expiresIn
) {
    public static RefreshResponse of(String accessToken, int expiresInSeconds) {
        return new RefreshResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
