package com.fieldservice.identity.token;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * Provider abstraction for the signing key set used to issue and verify RS256 JWTs.
 *
 * <p>Implementations may back this with an in-memory generated key (development / tests),
 * an AWS Secrets Manager entry, or an Azure Key Vault secret — the choice stays behind
 * this interface so security code never references a specific cloud provider.
 *
 * <p>The returned {@link JWKSet} may contain more than one key during a 90-day rotation
 * overlap window: one primary signing key (identified by {@code use=sig} and a specific
 * {@code kid}) and one or more outgoing verification-only keys. The JWT decoder accepts
 * any key in the set; the encoder uses the primary signing key only.
 */
public interface SigningKeyProvider {

    /**
     * Returns the current JWK set. The set must contain at least one RSA key suitable
     * for RS256 verification. During a rotation overlap the set contains two keys.
     *
     * @return immutable snapshot of the active key set
     */
    JWKSet getJwkSet();

    /**
     * Returns the kid value of the key that should be used for signing new tokens.
     * Must match a key present in {@link #getJwkSet()}.
     */
    String getPrimaryKid();
}
