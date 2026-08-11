package com.fieldservice.architecture.fixture;

import com.fieldservice.notification.internal.AlertSseEmitterRegistry;

/**
 * ⚠️ ARCHITECTURE TEST FIXTURE ONLY — DELIBERATELY NON-COMPLIANT ⚠️
 *
 * <p>This class exists solely to prove that the ArchUnit module-boundary rule in
 * {@link com.fieldservice.architecture.ModuleBoundaryTest} fires when a class outside
 * a module reaches into that module's {@code .internal} package.
 *
 * <p>{@link AlertSseEmitterRegistry} is in {@code notification.internal} — a package
 * that is not part of the notification module's public API contract.
 *
 * <p>This class must never be used in production code.
 */
@SuppressWarnings("unused")
public class CrossModuleInternalAccessFixture {

    /** Directly references notification.internal — deliberate module-boundary violation. */
    private AlertSseEmitterRegistry registry;
}
