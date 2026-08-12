package com.fieldservice.privacy.internal;

/** Result returned by a {@link DsarTransitionGuard} evaluation. */
sealed interface DsarGuardResult {
    record Satisfied()                           implements DsarGuardResult {}
    record Refused(String code, String message)  implements DsarGuardResult {}
}
