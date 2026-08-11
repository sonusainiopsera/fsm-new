package com.fieldservice.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * {@link SigningKeyProvider} backed by an RSA key held in JVM memory.
 *
 * <p>Used in development and integration tests where no managed secrets store is
 * available. The RSA key is generated at application startup by
 * {@link com.fieldservice.identity.config.PasswordEncoderConfig} and injected here.
 *
 * <p>Production deployments should replace this bean with a secrets-store-backed
 * adapter (AWS Secrets Manager, Azure Key Vault, etc.) before deploying to a
 * publicly reachable environment.
 */
public class InMemorySigningKeyProvider implements SigningKeyProvider {

    private final RSAKey rsaKey;
    private final JWKSet jwkSet;

    public InMemorySigningKeyProvider(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
        // Expose only the public components in the JWKSet — private key is never in the set
        this.jwkSet = new JWKSet(rsaKey.toPublicJWK());
    }

    @Override
    public JWKSet getJwkSet() {
        return jwkSet;
    }

    @Override
    public String getPrimaryKid() {
        return rsaKey.getKeyID();
    }
}
