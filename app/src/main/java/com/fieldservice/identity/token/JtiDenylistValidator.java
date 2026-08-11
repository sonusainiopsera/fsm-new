package com.fieldservice.identity.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link OAuth2TokenValidator} that rejects tokens whose {@code jti} claim appears in the
 * {@link JtiDenylist}.
 *
 * <p>Fail-closed: if Redis is unavailable the validator returns a failure result rather
 * than allowing the request through.
 *
 * <p>Tokens without a {@code jti} claim are rejected because jti is required for denylist
 * revocation to be possible — a token without jti cannot be individually revoked at logout.
 */
public class JtiDenylistValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(JtiDenylistValidator.class);

    private static final OAuth2Error MISSING_JTI = new OAuth2Error(
            "invalid_token", "Token is missing the jti claim.", null);
    private static final OAuth2Error DENYLISTED = new OAuth2Error(
            "invalid_token", "Token has been revoked.", null);
    private static final OAuth2Error DENYLIST_UNAVAILABLE = new OAuth2Error(
            "invalid_token", "Token validation service unavailable.", null);

    private final JtiDenylist denylist;

    public JtiDenylistValidator(JtiDenylist denylist) {
        this.denylist = denylist;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String jti = token.getId();
        if (jti == null || jti.isBlank()) {
            log.warn("jwt_missing_jti sub={}", token.getSubject());
            return OAuth2TokenValidatorResult.failure(MISSING_JTI);
        }

        try {
            if (denylist.isDenied(jti)) {
                log.warn("jwt_denylisted jti={} sub={}", jti, token.getSubject());
                return OAuth2TokenValidatorResult.failure(DENYLISTED);
            }
        } catch (JtiDenylist.JtiDenylistUnavailableException e) {
            log.error("jwt_denylist_check_failed_rejecting_fail_closed jti={}", jti, e);
            return OAuth2TokenValidatorResult.failure(DENYLIST_UNAVAILABLE);
        }

        return OAuth2TokenValidatorResult.success();
    }
}
