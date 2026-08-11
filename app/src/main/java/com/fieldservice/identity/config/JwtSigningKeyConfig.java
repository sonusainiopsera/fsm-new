package com.fieldservice.identity.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Provides the RSA key pair used for RS256 JWT signing.
 *
 * <p>Production deployments should set APP_AUTH_JWT_RSA_PRIVATE_KEY_PEM to load a stable
 * key. When the property is absent (local dev, test), an ephemeral 2048-bit key is
 * generated at startup — all issued tokens become invalid on restart.
 *
 * <p>WO-013 will replace this with a secrets-backed SigningKeyProvider. The KeyPair bean
 * interface is preserved for that migration.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtSigningKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtSigningKeyConfig.class);

    @Bean
    public KeyPair jwtKeyPair(AuthProperties props) {
        String pem = props.jwt().rsaPrivateKeyPem();
        if (pem != null && !pem.isBlank()) {
            throw new IllegalStateException(
                    "PEM key loading is reserved for WO-013; remove APP_AUTH_JWT_RSA_PRIVATE_KEY_PEM " +
                    "or implement the PEM loading path before setting it.");
        }
        log.warn("app.auth.jwt.rsa-private-key-pem is not set — " +
                "generating ephemeral RSA-2048 key pair. All tokens will be invalid after restart.");
        return generateEphemeralKeyPair();
    }

    @Bean
    public RSAPrivateKey jwtPrivateKey(KeyPair jwtKeyPair) {
        return (RSAPrivateKey) jwtKeyPair.getPrivate();
    }

    @Bean
    public RSAPublicKey jwtPublicKey(KeyPair jwtKeyPair) {
        return (RSAPublicKey) jwtKeyPair.getPublic();
    }

    private static KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available in this JVM", e);
        }
    }
}
