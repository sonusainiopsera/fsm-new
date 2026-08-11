package com.fieldservice.platform.security;

/**
 * Request-scoped accessor for the authenticated principal's {@link AccessScope}.
 *
 * <p>Implementations resolve the scope lazily from the SecurityContext on the first call
 * and cache it for the remainder of the request. Callers inject this interface rather than
 * using the SecurityContext directly, keeping controllers and services testable.
 *
 * <p>The canonical implementation is {@link RequestScopedAccessScope}.
 *
 * @see RequestScopedAccessScope
 */
public interface CurrentPrincipal {

    /**
     * Returns the {@link AccessScope} for the current HTTP request.
     *
     * @throws ScopedAccessDeniedException if the request is not authenticated or the scope
     *                                     cannot be resolved from the JWT
     */
    AccessScope get();
}
