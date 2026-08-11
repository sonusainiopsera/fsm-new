package com.fieldservice.aigateway.api;

import java.util.UUID;

/**
 * Immutable request for a vision-captioning call.
 *
 * <p>Image bytes are passed directly; no URL from a request payload is accepted
 * (OWASP A01 SSRF mitigation — the adapter uses only the configured provider endpoint).
 *
 * @param userId    caller identity — used for per-user cap enforcement
 * @param prompt    pre-grounded instruction describing what to caption
 * @param imageBytes raw image bytes (JPEG or PNG)
 * @param mimeType  MIME type, e.g. {@code image/jpeg}
 */
public record AiVisionRequest(
        UUID userId,
        String prompt,
        byte[] imageBytes,
        String mimeType
) {
    public AiVisionRequest {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must not be empty");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
    }
}
