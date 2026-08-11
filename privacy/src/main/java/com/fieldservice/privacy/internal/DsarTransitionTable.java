package com.fieldservice.privacy.internal;

import com.fieldservice.platform.security.Role;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.fieldservice.privacy.internal.DsarEvent.ACKNOWLEDGE;
import static com.fieldservice.privacy.internal.DsarEvent.FULFILL;
import static com.fieldservice.privacy.internal.DsarEvent.REJECT;
import static com.fieldservice.privacy.internal.DsarEvent.START_PROCESSING;
import static com.fieldservice.privacy.internal.DsarEvent.VERIFY;
import static com.fieldservice.privacy.internal.DsarEvent.WITHDRAW;
import static com.fieldservice.privacy.internal.DsarState.FULFILLED;
import static com.fieldservice.privacy.internal.DsarState.IDENTITY_PENDING;
import static com.fieldservice.privacy.internal.DsarState.IN_PROGRESS;
import static com.fieldservice.privacy.internal.DsarState.RECEIVED;
import static com.fieldservice.privacy.internal.DsarState.REJECTED;
import static com.fieldservice.privacy.internal.DsarState.VERIFIED;
import static com.fieldservice.privacy.internal.DsarState.WITHDRAWN;

/**
 * Single, immutable source of truth for all DSAR lifecycle rules.
 *
 * <p>Built once at class load from {@link Map#ofEntries} and wrapped in
 * {@link Collections#unmodifiableMap}. No external code can modify it.
 *
 * <p>Terminal states FULFILLED, REJECTED, and WITHDRAWN have zero outbound entries;
 * no event produces a transition out of a terminal state.
 *
 * <p>Modelled after the work order lifecycle pattern (WO-123).
 */
final class DsarTransitionTable {

    static final String GUARD_VERIFICATION_METHOD_REQUIRED = "dsar-verification-method-required";
    static final String GUARD_ARTIFACT_EXISTS = "dsar-artifact-exists";

    private static final Set<String> PRIVACY_ROLES = roles(Role.PRIVACY_ADMIN, Role.ADMIN);

    private static final Map<DsarTransitionKey, DsarTransitionDescriptor> TABLE =
            Collections.unmodifiableMap(Map.ofEntries(

                    // ── Intake → verification pending ────────────────────────────────
                    row(RECEIVED,        ACKNOWLEDGE,      IDENTITY_PENDING, PRIVACY_ROLES),
                    row(RECEIVED,        REJECT,           REJECTED,         PRIVACY_ROLES),
                    row(RECEIVED,        WITHDRAW,         WITHDRAWN,        PRIVACY_ROLES),

                    // ── Verification ────────────────────────────────────────────────
                    row(IDENTITY_PENDING, VERIFY,          VERIFIED,         PRIVACY_ROLES,
                            GUARD_VERIFICATION_METHOD_REQUIRED),
                    row(IDENTITY_PENDING, REJECT,          REJECTED,         PRIVACY_ROLES),
                    row(IDENTITY_PENDING, WITHDRAW,        WITHDRAWN,        PRIVACY_ROLES),

                    // ── Verified → assembly ─────────────────────────────────────────
                    row(VERIFIED,        START_PROCESSING, IN_PROGRESS,      PRIVACY_ROLES),
                    row(VERIFIED,        WITHDRAW,         WITHDRAWN,        PRIVACY_ROLES),
                    row(VERIFIED,        REJECT,           REJECTED,         PRIVACY_ROLES),

                    // ── Assembly → terminal ─────────────────────────────────────────
                    row(IN_PROGRESS,     FULFILL,          FULFILLED,        PRIVACY_ROLES,
                            GUARD_ARTIFACT_EXISTS),
                    row(IN_PROGRESS,     REJECT,           REJECTED,         PRIVACY_ROLES),
                    row(IN_PROGRESS,     WITHDRAW,         WITHDRAWN,        PRIVACY_ROLES)
            ));

    private DsarTransitionTable() {}

    // ── Public API ──────────────────────────────────────────────────────────────

    static Optional<DsarTransitionDescriptor> resolve(DsarState fromState, DsarEvent event) {
        return Optional.ofNullable(TABLE.get(new DsarTransitionKey(fromState, event)));
    }

    static Set<DsarEvent> legalEventsFrom(DsarState state) {
        return TABLE.keySet().stream()
                .filter(k -> k.fromState() == state)
                .map(DsarTransitionKey::event)
                .collect(Collectors.toUnmodifiableSet());
    }

    static Set<String> allReferencedGuardIds() {
        return TABLE.values().stream()
                .flatMap(d -> d.guardIds().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static Map.Entry<DsarTransitionKey, DsarTransitionDescriptor> row(
            DsarState from, DsarEvent event, DsarState to, Set<String> rolesArg,
            String... guardIds) {
        return Map.entry(
                new DsarTransitionKey(from, event),
                new DsarTransitionDescriptor(to, rolesArg, Arrays.asList(guardIds)));
    }

    private static Set<String> roles(String... roleValues) {
        return Set.of(roleValues);
    }
}
