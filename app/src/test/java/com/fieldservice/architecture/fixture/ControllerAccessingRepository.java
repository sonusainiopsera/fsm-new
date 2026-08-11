package com.fieldservice.architecture.fixture;

import com.fieldservice.domain.workorder.WorkOrderRepository;
import org.springframework.web.bind.annotation.RestController;

/**
 * ⚠️ ARCHITECTURE TEST FIXTURE ONLY — DELIBERATELY NON-COMPLIANT ⚠️
 *
 * <p>This class exists solely to prove that the ArchUnit layered-architecture rule in
 * {@link com.fieldservice.architecture.LayeredArchitectureTest} fires when a
 * {@code @RestController} class directly depends on a JPA repository.
 *
 * <p>This class must never be used in production code or wired as a Spring bean.
 */
@RestController
public class ControllerAccessingRepository {

    /** Direct repository field — deliberate layering violation. */
    @SuppressWarnings("unused")
    private WorkOrderRepository workOrderRepository;
}
