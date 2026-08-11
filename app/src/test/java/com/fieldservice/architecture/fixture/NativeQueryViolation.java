package com.fieldservice.architecture.fixture;

import jakarta.persistence.EntityManager;

/**
 * ⚠️ ARCHITECTURE TEST FIXTURE ONLY — DELIBERATELY NON-COMPLIANT ⚠️
 *
 * <p>This class exists solely to prove that the ArchUnit injection-risk rule in
 * {@link com.fieldservice.architecture.InjectionAndCryptoRulesTest} fires when code
 * calls {@link EntityManager#createNativeQuery(String)} directly.
 *
 * <p>Direct use of {@code createNativeQuery} with a runtime string bypasses the ORM
 * query model and can expose SQL injection risk. Use {@code @Query} with named parameters.
 *
 * <p>This class must never be used in production code.
 */
@SuppressWarnings("unused")
public class NativeQueryViolation {

    private final EntityManager entityManager;

    public NativeQueryViolation(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Calls createNativeQuery — deliberate injection-risk violation. */
    public Object runNativeQuery(String tableName) {
        return entityManager.createNativeQuery("SELECT * FROM " + tableName);
    }
}
