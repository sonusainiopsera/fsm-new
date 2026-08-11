/**
 * Public API surface of the platform module.
 *
 * <h2>Non-disclosure contract for scoped resources</h2>
 * <p>For all entities implementing
 * {@link com.fieldservice.platform.persistence.ScopedEntity}, absent and
 * out-of-scope identifiers <em>deliberately return the same 403 response</em>.
 * A caller cannot distinguish "this record does not exist" from "this record
 * exists but you cannot see it" via HTTP status code, body, body length, header,
 * or timing.
 *
 * <p>This decision is intentional and documented here (BR-19, OWASP A01).
 * Future additions to the API surface must not introduce any mechanism that
 * breaks this non-disclosure guarantee — including pagination totals, link headers,
 * ETag values, or error message text.
 *
 * <h2>Opt-out</h2>
 * <p>The sole authorised opt-out is the analytics read model, accessed via
 * {@link com.fieldservice.platform.security.UnscopedRead @UnscopedRead}-annotated
 * methods. Every usage must appear in
 * {@code platform/src/main/resources/unscoped-read-allowlist.txt} and be
 * code-reviewed. The {@code UnscopedReadAllowListTest} enforces this at build time.
 */
package com.fieldservice.platform.api;
