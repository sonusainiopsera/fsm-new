package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * RS256 JWT implementation of {@link TokenIssuer}.
 *
 * <p>Claims issued: {@code sub} (userId), {@code roles} (list of role names),
 * {@code jti} (UUID), {@code iat}, {@code exp} (15 minutes), {@code iss}, {@code aud}.
 *
 * <p>The refresh handle is a 256-bit (32 bytes) {@link SecureRandom} value encoded
 * as base64url without padding. Only the SHA-256 hex digest of this value is persisted;
 * the plaintext handle is returned here and immediately set in an HttpOnly cookie.
 */
@Service
public class JwtTokenIssuer implements TokenIssuer {

    private static final int    REFRESH_HANDLE_BYTES = 32;   // 256 bits
    private static final SecureRandom SECURE_RANDOM  = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final String     issuer;
    private final String     audience;
    private final Duration   accessTokenTtl;

    public JwtTokenIssuer(
            JwtEncoder jwtEncoder,
            @Value("${app.auth.issuer:https://auth.fieldservice.local}") String issuer,
            @Value("${app.auth.audience:field-service-api}")             String audience,
            @Value("${app.auth.access-token-ttl:PT15M}")                 Duration accessTokenTtl) {
        this.jwtEncoder     = jwtEncoder;
        this.issuer         = issuer;
        this.audience       = audience;
        this.accessTokenTtl = accessTokenTtl;
    }

    @Override
    public TokenBundle issue(AppUser user, List<AppRole> roles) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessTokenTtl);

        List<String> roleNames = roles.stream().map(AppRole::name).toList();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("roles", roleNames)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(exp)
                .issuer(issuer)
                .audience(List.of(audience))
                .build();

        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();

        String refreshHandle = generateRefreshHandle();

        return new TokenBundle(accessToken, refreshHandle, exp);
    }

    private static String generateRefreshHandle() {
        byte[] bytes = new byte[REFRESH_HANDLE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
