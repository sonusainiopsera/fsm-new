package com.fieldservice.identity.api.dto;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummary user
) {
    public record UserSummary(UUID id, String displayName, List<String> roles) {}

    public static LoginResponse of(
            String accessToken, long expiresIn, UUID userId, String displayName, List<String> roles) {
        return new LoginResponse(
                accessToken,
                "Bearer",
                expiresIn,
                new UserSummary(userId, displayName, roles));
    }
}
