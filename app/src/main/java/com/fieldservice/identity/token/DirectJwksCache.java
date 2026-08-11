package com.fieldservice.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * No-op {@link JwksCache} that delegates directly to {@link SigningKeyProvider} on
 * every call. Active only when Redis is unavailable (test profile without Redis).
 */
@Component
@ConditionalOnMissingBean(JwksCache.class)
public class DirectJwksCache implements JwksCache {

    private final SigningKeyProvider keyProvider;

    public DirectJwksCache(SigningKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @Override
    public JWKSet getJwkSet() {
        return keyProvider.getVerificationJwkSet();
    }
}
