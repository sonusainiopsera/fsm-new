package com.fieldservice.architecture.fixture;

import com.fieldservice.domain.workorder.WorkOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ⚠️ ARCHITECTURE TEST FIXTURE ONLY — DELIBERATELY NON-COMPLIANT ⚠️
 *
 * <p>This class exists solely to prove that the ArchUnit DTO-boundary rule in
 * {@link com.fieldservice.architecture.DtoBoundaryTest} fires when a controller
 * method signature directly exposes a JPA entity type.
 *
 * <p>This class must never be used in production code or wired as a Spring bean.
 */
@RestController
public class ControllerReturningEntity {

    /** Method returns a JPA entity directly — deliberate DTO-boundary violation. */
    @GetMapping("/fixture/entity-leak")
    public ResponseEntity<WorkOrder> getEntity() {
        return ResponseEntity.ok(null);
    }

    /** Method accepts a JPA entity as parameter — deliberate DTO-boundary violation. */
    @GetMapping("/fixture/entity-param")
    public void acceptEntity(WorkOrder entity) {
        // deliberate violation
    }
}
