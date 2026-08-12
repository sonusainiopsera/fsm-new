package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarState;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.fieldservice.privacy.api.DsarEvent.*;
import static com.fieldservice.privacy.api.DsarState.*;

/**
 * Immutable declarative DSAR lifecycle transition table.
 *
 * <p>This is the single source of truth for every DSAR lifecycle rule.
 * No other production class may branch on {@link DsarState} directly.
 *
 * <p>Guard ids:
 * <ul>
 *   <li>{@code "dsar.identity.verified"} — enforces that identity is verified before export</li>
 * </ul>
 */
final class DsarTransitionTable {

    private static final Map<DsarTransitionKey, DsarTransitionDescriptor> TABLE =
            Collections.unmodifiableMap(Map.ofEntries(

                    // ---- RECEIVED -----------------------------------------------
                    entry(RECEIVED, BEGIN_VERIFICATION, IDENTITY_PENDING,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),
                    entry(RECEIVED, VERIFY_DIRECT, VERIFIED,
                            Set.of("PRIVACY_ADMIN", "ADMIN"),
                            List.of()),
                    entry(RECEIVED, REJECT, REJECTED,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),
                    entry(RECEIVED, WITHDRAW, WITHDRAWN,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),

                    // ---- IDENTITY_PENDING ----------------------------------------
                    entry(IDENTITY_PENDING, RECORD_VERIFICATION, VERIFIED,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),
                    entry(IDENTITY_PENDING, REJECT, REJECTED,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),
                    entry(IDENTITY_PENDING, WITHDRAW, WITHDRAWN,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),

                    // ---- VERIFIED -----------------------------------------------
                    entry(VERIFIED, CLAIM, IN_PROGRESS,
                            Set.of("PRIVACY_ADMIN", "ADMIN", "SYSTEM"),
                            List.of("dsar.identity.verified")),
                    entry(VERIFIED, REJECT, REJECTED,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),
                    entry(VERIFIED, WITHDRAW, WITHDRAWN,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),

                    // ---- IN_PROGRESS -----------------------------------------------
                    entry(IN_PROGRESS, FULFIL, FULFILLED,
                            Set.of("PRIVACY_ADMIN", "ADMIN", "SYSTEM"),
                            List.of("dsar.identity.verified")),
                    entry(IN_PROGRESS, REJECT, REJECTED,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of()),
                    entry(IN_PROGRESS, WITHDRAW, WITHDRAWN,
                            Set.of("PRIVACY_ADMIN", "ADMIN"), List.of())

                    // FULFILLED, REJECTED, WITHDRAWN are terminal — no outbound entries
            ));

    static Optional<DsarTransitionDescriptor> resolve(DsarState fromState, DsarEvent event) {
        return Optional.ofNullable(TABLE.get(new DsarTransitionKey(fromState, event)));
    }

    /** Exposed for table-immutability tests only. */
    static Map<DsarTransitionKey, DsarTransitionDescriptor> rawTable() {
        return TABLE;
    }

    private DsarTransitionTable() {}

    private static Map.Entry<DsarTransitionKey, DsarTransitionDescriptor> entry(
            DsarState from, DsarEvent event, DsarState to,
            Set<String> roles, List<String> guardIds) {
        return Map.entry(new DsarTransitionKey(from, event),
                new DsarTransitionDescriptor(to, Set.copyOf(roles), List.copyOf(guardIds)));
    }
}
