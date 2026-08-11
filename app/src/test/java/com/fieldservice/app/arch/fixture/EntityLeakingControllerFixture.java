package com.fieldservice.app.arch.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Deliberately non-compliant fixture used by {@code DtoBoundaryTest} to prove
 * that the DTO-boundary rule fires when a controller method returns a JPA entity
 * instead of a DTO.
 *
 * <p>This class must never be registered as a Spring bean. It lives in the test-only
 * {@code arch.fixture} sub-package so the production import scope excludes it.
 */
@SuppressWarnings("unused")
@RestController
public class EntityLeakingControllerFixture {

    /**
     * Intentionally returns a JPA {@code @Entity} directly — violates the DTO-boundary rule.
     */
    public LeakyEntity getEntity(UUID id) {
        return new LeakyEntity();
    }

    /** A JPA entity that must not appear in controller signatures. */
    @Entity
    static class LeakyEntity {
        @Id
        UUID id;
    }
}
