package com.fieldservice.identity.application;

import com.fieldservice.identity.config.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
class JwtTokenIssuer implements TokenIssuer {

    private final RSAPrivateKey privateKey;
    private final String issuer;
    private final String audience;
    private final long accessTokenTtlSeconds;

    JwtTokenIssuer(RSAPrivateKey jwtPrivateKey, AuthProperties authProperties) {
        this.privateKey = jwtPrivateKey;
        this.issuer = authProperties.jwt().issuer();
        this.audience = authProperties.jwt().audience();
        this.accessTokenTtlSeconds = authProperties.jwt().accessTokenTtlSeconds();
    }

    @Override
    public String issueAccessToken(UUID userId, String email, List<String> roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("roles", roles)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(accessTokenTtlSeconds)))
                .issuer(issuer)
                .audience(audience)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                claims);
        try {
            signedJWT.sign(new RSASSASigner(privateKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
        return signedJWT.serialize();
    }
}
