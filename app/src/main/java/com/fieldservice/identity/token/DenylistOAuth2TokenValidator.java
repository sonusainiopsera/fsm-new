package com.fieldservice.identity.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link OAuth2TokenValidator} that rejects tokens whose jti is in the revocation denylist.
 *
 * <p>Fail-closed: if the denylist is unavailable the token is rejected and an operational
 * alert is raised. The rejection reason is never disclosed in the 401 response body.
 */
public class DenylistOAuth2TokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(DenylistOAuth2TokenValidator.class);
    private static final OAuth2Error REVOKED_ERROR =
            new OAuth2Error("invalid_token", "Token has been revoked.", null);
    private static final OAuth2Error UNAVAILABLE_ERROR =
            new OAuth2Error("invalid_token", "Token validation is temporarily unavailable.", null);

    private final JtiDenylist denylist;

    public DenylistOAuth2TokenValidator(JtiDenylist denylist) {
        this.denylist = denylist;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String jti = token.getId();
        if (jti == null || jti.isBlank()) {
            return OAuth2TokenValidatorResult.failure(REVOKED_ERROR);
        }
        try {
            if (denylist.isRevoked(jti)) {
                log.info("audit.token_revoked jti={}", jti);
                return OAuth2TokenValidatorResult.failure(REVOKED_ERROR);
            }
        } catch (JtiDenylist.DenylistUnavailableException e) {
            log.warn("alert.denylist_unavailable jti={} msg={}", jti, e.getMessage());
            return OAuth2TokenValidatorResult.failure(UNAVAILABLE_ERROR);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
