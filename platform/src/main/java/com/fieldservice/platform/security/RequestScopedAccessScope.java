package com.fieldservice.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped provider for the authenticated principal's {@link AccessScope}.
 *
 * <p>The scope is resolved lazily on first access and then cached for the remainder of the
 * request, ensuring that JWT claims are parsed exactly once per HTTP request. Callers
 * (controllers, services, {@link ScopedQueryExecutor}) inject this bean and call
 * {@link #get()} rather than reading the SecurityContext themselves.
 *
 * <p>Marked {@code @RequestScope} so Spring creates a new proxy instance for every request
 * and discards it at the end, eliminating any risk of stale scope leaking between requests.
 */
@Component
@RequestScope
public class RequestScopedAccessScope {

    private final AccessScopeResolver resolver;
    private AccessScope cached;

    public RequestScopedAccessScope(AccessScopeResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Returns the {@link AccessScope} for the current request, resolving it from the
     * SecurityContext on the first call.
     *
     * @throws ScopedAccessDeniedException if the current request is not authenticated or
     *                                     the JWT cannot be resolved
     */
    public AccessScope get() {
        if (cached == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            cached = resolver.resolve(auth);
        }
        return cached;
    }
}
