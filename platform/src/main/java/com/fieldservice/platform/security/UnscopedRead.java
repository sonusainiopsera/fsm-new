package com.fieldservice.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-out annotation for reads that are genuinely non-scoped, such as aggregate analytics
 * queries that must see the full dataset.
 *
 * <p><strong>Usage is restricted to the analytics read model.</strong> Applying this
 * annotation to any class outside the analytics package will cause
 * {@code UnscopedReadAllowListTest} to fail, blocking the CI build.
 *
 * <h3>Review gate</h3>
 * A reflective test ({@code UnscopedReadAllowListTest}) enumerates all usages of this
 * annotation at test time and compares the discovered set against the committed allow-list
 * file at {@code src/test/resources/unscoped-read-allowlist.txt}. Any new usage not on the
 * allow-list causes the test to fail — the opt-out surface cannot grow silently.
 *
 * <h3>Required attributes</h3>
 * <ul>
 *   <li>{@link #justification()} — must contain a non-blank business justification
 *       explaining why this read is safe without a scope predicate.</li>
 *   <li>{@link #approvedBy()} — reference to the code-review approval (PR/ticket
 *       number).</li>
 * </ul>
 *
 * @see UnscopedReadAllowListTest (app module test)
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UnscopedRead {

    /**
     * Mandatory business justification explaining why this read does not require a
     * row-scope predicate and how data isolation is preserved by other means.
     */
    String justification();

    /**
     * Reference to the code-review approval that authorised this opt-out (e.g.
     * "Approved in PR-42 by @alice").
     */
    String approvedBy() default "";
}
