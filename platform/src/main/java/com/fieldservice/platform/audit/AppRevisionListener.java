package com.fieldservice.platform.audit;

import org.hibernate.envers.RevisionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Envers {@link RevisionListener} that populates actor-aware fields on every new revision.
 *
 * <p>Reads:
 * <ul>
 *   <li>{@code actorUserId} — JWT subject claim from the current {@link JwtAuthenticationToken};
 *       falls back to {@code "SYSTEM"} for background jobs with no authenticated principal.</li>
 *   <li>{@code actorRole} — first authority stripped of the {@code ROLE_} prefix.</li>
 *   <li>{@code traceId} — MDC key {@code "traceId"} set by the request-scoped trace filter.</li>
 *   <li>{@code clientIp} — MDC key {@code "clientIp"} set by the request-scoped trace filter.</li>
 * </ul>
 *
 * <p>This listener must never fail the business transaction. All resolution is wrapped in a
 * try-catch and any unresolvable field is left as {@code null} (except actorUserId which always
 * falls back to {@code "SYSTEM"}).
 */
public class AppRevisionListener implements RevisionListener {

    static final String SYSTEM_ACTOR = "SYSTEM";

    private static final Logger log = LoggerFactory.getLogger(AppRevisionListener.class);

    @Override
    public void newRevision(Object revisionEntity) {
        AppRevision revision = (AppRevision) revisionEntity;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                revision.setActorUserId(jwt.getToken().getSubject());
                jwt.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a.startsWith("ROLE_"))
                        .findFirst()
                        .ifPresent(role -> revision.setActorRole(role.substring(5)));
            } else {
                revision.setActorUserId(SYSTEM_ACTOR);
                if (auth != null) {
                    log.warn("audit: non-JWT principal '{}' — recording SYSTEM actor",
                            auth.getClass().getSimpleName());
                }
            }
        } catch (Exception ex) {
            log.warn("audit: failed to resolve actor from SecurityContext; using SYSTEM actor", ex);
            revision.setActorUserId(SYSTEM_ACTOR);
        }

        try {
            revision.setTraceId(MDC.get("traceId"));
            revision.setClientIp(MDC.get("clientIp"));
        } catch (Exception ex) {
            log.warn("audit: failed to read traceId/clientIp from MDC", ex);
        }
    }
}
