package com.fieldservice.app.arch.fixture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Deliberately non-compliant fixture used by {@code LayeredArchitectureTest} to prove
 * that the layering rule fires when a {@code @RestController} directly injects a
 * repository interface.
 *
 * <p>This class must never be registered as a Spring bean or used in production code.
 * It lives in the test-only {@code arch.fixture} sub-package specifically to be
 * excluded from the production import scope.
 */
@SuppressWarnings("unused")
@RestController
public class ViolatingControllerFixture {

    /** Intentionally injects a bare JPA repository — violates the layering rule. */
    private final FakeRepository repository;

    public ViolatingControllerFixture(FakeRepository repository) {
        this.repository = repository;
    }

    /** Bare JPA repository — no service layer — for layering-rule violation test only. */
    interface FakeRepository extends JpaRepository<Object, UUID> {}
}
