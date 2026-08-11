package com.fieldservice.platform.audit;

import org.hibernate.envers.RevisionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Envers {@link RevisionListener} that populates actor attribution on every new revision.
 *
 * <p>Reads the authenticated principal from the Spring {@code SecurityContextHolder} (populated
 * by the JWT filter chain for HTTP requests) and the {@code traceId} / {@code clientIp} from
 * the SLF4J MDC populated by the request logging filter.
 *
 * <p>When no authenticated principal exists (worker profile jobs, scheduled sweeps, Flyway
 * migrations), a synthetic {@code system} actor is recorded and a WARN log is emitted.
 * The actor field is never left null; a null actor would break BR-21 attribution requirements.
 *
 * <p>This class is not a Spring bean — Envers instantiates it directly.  It may not use
 * constructor injection; it reads ThreadLocal state (SecurityContextHolder, MDC) directly.
 */
public class AuditRevisionListener implements RevisionListener {

    private static final Logger log = LoggerFactory.getLogger(AuditRevisionListener.class);

    private static final String SYSTEM_ACTOR_ID = "system";
    private static final String SYSTEM_ACTOR_ROLE = "SYSTEM";
    private static final String UNKNOWN_IP = "unknown";

    @Override
    public void newRevision(Object revisionEntity) {
        AuditRevisionEntity rev = (AuditRevisionEntity) revisionEntity;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        String actorUserId;
        String actorRole;

        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            actorUserId = auth.getName();
            actorRole = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5))
                    .findFirst()
                    .orElse("UNKNOWN");
        } else {
            // Worker profile / scheduled job / migration — no authenticated principal
            actorUserId = SYSTEM_ACTOR_ID;
            actorRole = SYSTEM_ACTOR_ROLE;
            log.warn("AuditRevisionListener: no authenticated principal; recording synthetic system actor for BR-21");
        }

        rev.setActorUserId(actorUserId);
        rev.setActorRole(actorRole);
        rev.setTraceId(MDC.get("traceId"));

        String clientIp = MDC.get("clientIp");
        rev.setClientIp(clientIp != null ? clientIp : UNKNOWN_IP);
    }
}
