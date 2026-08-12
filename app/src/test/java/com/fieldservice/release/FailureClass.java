package com.fieldservice.release;

/**
 * Coarse failure classification for artifact consumers and on-call routing.
 *
 * <ul>
 *   <li>ENVIRONMENT_SETUP — missing account, part, or configuration; must not be silently passed.</li>
 *   <li>TRANSPORT — network error, timeout, or unexpected HTTP status unrelated to an invariant.</li>
 *   <li>INVARIANT_VIOLATION — the invariant under test was violated; triggers rollback.</li>
 *   <li>SMOKE_PATH — the basic health / login / create path failed.</li>
 * </ul>
 */
public enum FailureClass {
    ENVIRONMENT_SETUP,
    TRANSPORT,
    INVARIANT_VIOLATION,
    SMOKE_PATH
}
