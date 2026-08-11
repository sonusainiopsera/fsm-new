package com.fieldservice.aigateway.api;

/**
 * Provider-agnostic vision captioning request.
 * The image is identified by its object-store key (never a user-supplied URL).
 */
public record AiVisionRequest(
        String userId,
        String objectStoreKey,
        String prompt) {}
