package com.fieldservice.privacy.internal;

import org.springframework.lang.Nullable;

/**
 * Request-scoped context data passed to {@link DsarTransitionGuard} implementations.
 *
 * @param verificationMethod the identity verification method provided by the actor (may be null)
 * @param note               optional note accompanying the transition
 */
record DsarTransitionContext(
        @Nullable String verificationMethod,
        @Nullable String note
) {}
