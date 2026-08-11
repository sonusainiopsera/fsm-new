package com.fieldservice.platform.audit;

import org.hibernate.envers.RevisionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Populates actor attribution fields on each new {@link AppRevisionEntity}.
 *
 * <p>Reads the authenticated principal from the Spring {@code SecurityContextHolder},
 * the request traceId from MDC (populated by {@code TraceIdFilter}), and the client IP
 * from the active servlet request. When no authenticated principal is present (worker
 * jobs, scheduled sweeps, migrations) the listener records an explicit {@code system}
 * synthetic actor rather than leaving the column null.
 */
public class AppRevisionListener implements RevisionListener {

    private static final Logger log = LoggerFactory.getLogger(AppRevisionListener.class);

    private static final String SYSTEM_ACTOR = "system";
    private static final String SYSTEM_ROLE = "SYSTEM";
    private static final String MDC_TRACE_KEY = "traceId";

    @Override
    public void newRevision(Object revisionEntity) {
        AppRevisionEntity rev = (AppRevisionEntity) revisionEntity;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            rev.setActorUserId(auth.getName());
            rev.setActorRole(auth.getAuthorities().stream()
                .findFirst()
                .map(Object::toString)
                .orElse(null));
        } else {
            rev.setActorUserId(SYSTEM_ACTOR);
            rev.setActorRole(SYSTEM_ROLE);
            log.warn("No authenticated principal in revision context; recording synthetic system actor");
        }

        rev.setTraceId(MDC.get(MDC_TRACE_KEY));

        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            rev.setClientIp(sra.getRequest().getRemoteAddr());
        }
    }
}
