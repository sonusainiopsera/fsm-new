package com.fieldservice.platform.security;

import java.lang.annotation.*;

/**
 * Opt-out annotation permitting a repository method or class to perform a read
 * that bypasses the standard {@link AccessScopePredicateFactory} row-scope enforcement.
 *
 * <p><strong>Use only on the analytics read model.</strong> Every other read over
 * a scoped entity must go through
 * {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}. Adding a new
 * usage requires:
 * <ol>
 *   <li>A non-trivial {@code justification} documenting why scoping cannot apply.</li>
 *   <li>An entry in {@code platform/src/main/resources/unscoped-read-allowlist.txt}.</li>
 *   <li>A code-review approval on the allowlist change.</li>
 * </ol>
 *
 * <p>The test {@code UnscopedReadAllowListTest} reflectively enumerates every
 * {@code @UnscopedRead} usage and asserts it appears in the committed allowlist,
 * so the exception surface cannot grow silently.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UnscopedRead {

    /**
     * Mandatory human-readable justification explaining why this read bypasses
     * row-scope enforcement. Must be specific and non-trivial.
     */
    String justification();
}
