package com.fieldservice.identity.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Default {@link SigningKeyProvider} backed by the ephemeral RSA key pair from
 * {@link com.fieldservice.identity.config.JwtSigningKeyConfig}.
 *
 * <p>Active only when no other {@link SigningKeyProvider} is present in the context.
 * Production deployments replace this with a secrets-store-backed adapter.
 */
@Component
@ConditionalOnMissingBean(SigningKeyProvider.class)
public class EphemeralRsaSigningKeyProvider implements SigningKeyProvider {

    private static final String PRIMARY_KID = "primary";

    private final RSAKey rsaKey;

    public EphemeralRsaSigningKeyProvider(KeyPair jwtKeyPair) {
        this.rsaKey = new RSAKey.Builder((RSAPublicKey) jwtKeyPair.getPublic())
                .privateKey((RSAPrivateKey) jwtKeyPair.getPrivate())
                .keyID(PRIMARY_KID)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }

    @Override
    public JWK getPrimarySigningKey() {
        return rsaKey;
    }

    @Override
    public JWKSet getVerificationJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }
}
