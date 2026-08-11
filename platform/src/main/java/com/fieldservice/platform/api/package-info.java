/**
 * Public API surface of the platform module.
 *
 * <h2>Non-disclosure design decision</h2>
 * <p>For all reads against {@link com.fieldservice.platform.persistence.ScopedEntity scoped
 * entities}, a missing resource and an out-of-scope resource are deliberately collapsed into
 * a single HTTP 403 response with the uniform error envelope. This is an intentional,
 * security-critical design decision (BR-19 / OWASP A01):
 * <ul>
 *   <li>Returning 404 for a nonexistent resource and 403 for an out-of-scope resource would
 *       allow a cross-role probe to enumerate valid identifiers — the 403 response reveals
 *       existence.</li>
 *   <li>Returning 403 for both cases provides no information to the caller about whether
 *       the resource exists in the system, satisfying the deny-by-default and no-existence-
 *       disclosure requirements.</li>
 * </ul>
 *
 * <h2>Opt-out</h2>
 * <p>Reads that are genuinely non-scoped (e.g. aggregate analytics over the full dataset)
 * must be explicitly annotated with
 * {@link com.fieldservice.platform.security.UnscopedRead @UnscopedRead} with a mandatory
 * justification and approval reference. A reflective test (
 * {@code UnscopedReadAllowListTest} in the app module) enumerates every usage and compares
 * it against a committed allow-list; any undeclared usage fails the build.
 *
 * <h2>Row-scope predicate requirement</h2>
 * <p>The scope predicate must appear in the SQL WHERE clause — post-fetch filtering in Java
 * is forbidden because it allows counts and totals to leak out-of-scope row counts. All
 * reads over scoped entities must go through
 * {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 */
package com.fieldservice.platform.api;
