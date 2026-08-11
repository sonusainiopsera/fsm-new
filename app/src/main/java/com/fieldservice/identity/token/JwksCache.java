package com.fieldservice.identity.token;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * Caches the JWKS document returned by {@link SigningKeyProvider} to avoid repeated
 * key-material fetches on every token verification request.
 *
 * <p>The Redis-backed implementation stores the public JWKSet for 600 seconds and
 * emits hit, miss and refresh counter metrics. The in-memory fallback delegates
 * directly to the provider on each call (used when Redis is unavailable).
 */
public interface JwksCache {

    /**
     * Returns the current verification JWKSet, from cache if available.
     *
     * <p>The JWKSet contains public key material only and is safe to pass to
     * the NimbusJwtDecoder's JWKSource.
     */
    JWKSet getJwkSet();
}
