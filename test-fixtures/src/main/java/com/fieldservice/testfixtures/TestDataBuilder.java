package com.fieldservice.testfixtures;

import java.util.UUID;

/**
 * Shared builder helpers for entity and DTO test data.
 * Centralises test data construction so all stories draw from a single fixture
 * foundation rather than duplicating magic strings.
 */
public final class TestDataBuilder {

    private TestDataBuilder() {
        throw new UnsupportedOperationException("Utility class");
    }

    // -------------------------------------------------------------------------
    // Identity / Principal helpers
    // -------------------------------------------------------------------------

    /** Returns a deterministic dispatcher principal ID for use in tests. */
    public static String dispatcherPrincipalId() {
        return "dispatcher-" + UUID.nameUUIDFromBytes("test-dispatcher".getBytes());
    }

    /** Returns a deterministic technician principal ID for use in tests. */
    public static String technicianPrincipalId() {
        return "tech-" + UUID.nameUUIDFromBytes("test-technician".getBytes());
    }

    /** Returns a deterministic customer principal ID for use in tests. */
    public static String customerPrincipalId() {
        return "customer-" + UUID.nameUUIDFromBytes("test-customer".getBytes());
    }

    // -------------------------------------------------------------------------
    // Tenant helpers
    // -------------------------------------------------------------------------

    /** Returns the default test tenant ID. */
    public static String defaultTenantId() {
        return "tenant-test-001";
    }

    // -------------------------------------------------------------------------
    // ID generation helpers
    // -------------------------------------------------------------------------

    /** Generates a random aggregate ID suitable for test records. */
    public static String randomId() {
        return UUID.randomUUID().toString();
    }

    /** Generates a deterministic ID for a given seed string. */
    public static String deterministicId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes()).toString();
    }
}
