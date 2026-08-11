package com.fieldservice.identity.token;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

/**
 * Provides RSA signing keys for JWT issuance and verification.
 *
 * <p>The interface decouples the security chain from the secrets-store implementation:
 * production deployments inject keys from AWS Secrets Manager or Azure Key Vault behind
 * a concrete adapter; tests use the ephemeral adapter backed by the JwtSigningKeyConfig key pair.
 *
 * <p>Key rotation: the JWKSet returned by {@link #getVerificationJwkSet()} may contain
 * two keys simultaneously — the primary (current signing key) and an outgoing key that
 * remains valid for in-flight tokens during the 90-day rotation overlap window. The
 * {@link #getPrimarySigningKey()} identifies which key to use for new token issuance.
 */
public interface SigningKeyProvider {

    /**
     * Returns the JWK used to sign new access tokens. The key includes private key material
     * and must never leave the signing service boundary.
     */
    JWK getPrimarySigningKey();

    /**
     * Returns the public JWKSet used for signature verification. Contains public key material
     * only — safe to expose via the JWKS endpoint. May contain two entries during rotation.
     */
    JWKSet getVerificationJwkSet();
}
