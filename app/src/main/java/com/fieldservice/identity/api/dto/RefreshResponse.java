package com.fieldservice.identity.api.dto;

/**
 * Response body for POST /api/v1/auth/refresh.
 *
 * <p>Carries only token metadata — no user info, no handle, no family identifier.
 */
public record RefreshResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {}
