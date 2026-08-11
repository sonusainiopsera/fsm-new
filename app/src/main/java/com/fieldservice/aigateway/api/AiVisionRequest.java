package com.fieldservice.aigateway.api;

import java.util.Objects;

/**
 * Request for a vision-captioning call. The image URL must reference internal object storage
 * only; callers must never forward a URL that originated from user input.
 *
 * @param userId      identifier of the authenticated user
 * @param operationId stable identifier for the calling operation (e.g. "photo-caption")
 * @param imageUrl    pre-validated internal storage URL — never from user input (SSRF risk)
 * @param prompt      optional guidance prompt for the captioning model
 */
public record AiVisionRequest(
        String userId,
        String operationId,
        String imageUrl,
        String prompt
) {
    public AiVisionRequest {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(imageUrl, "imageUrl");
        if (userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");
        if (imageUrl.isBlank()) throw new IllegalArgumentException("imageUrl must not be blank");
    }
}
