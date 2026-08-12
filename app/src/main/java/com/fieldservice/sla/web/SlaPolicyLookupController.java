package com.fieldservice.sla.web;

import com.fieldservice.sla.SlaPolicyUnavailableException;
import com.fieldservice.sla.domain.SlaPolicy;
import com.fieldservice.sla.internal.SlaPolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

/**
 * Dispatcher-accessible SLA policy lookup — returns the active policy for a
 * priority so the creation form can preview derived deadlines before submission.
 *
 * <p>Accessible to all authenticated roles that can create work orders.
 * Returns 404 with SLA_POLICY_MISSING code when no active policy is configured
 * for the requested priority.
 */
@RestController
@RequestMapping("/api/v1/sla-policies")
public class SlaPolicyLookupController {

    private final SlaPolicyService slaPolicyService;

    public SlaPolicyLookupController(SlaPolicyService slaPolicyService) {
        this.slaPolicyService = slaPolicyService;
    }

    /**
     * Returns the active SLA policy for the given priority tier.
     *
     * @param priority work order priority code (e.g. HIGH, NORMAL, LOW)
     * @return policy with response and resolution minutes plus at-risk fraction
     */
    @GetMapping("/by-priority/{priority}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'DISPATCHER', 'CUSTOMER')")
    public ResponseEntity<SlaPolicyLookupResponse> getByPriority(@PathVariable String priority) {
        Optional<SlaPolicy> policy = slaPolicyService.resolve(priority, Instant.now());
        return policy
                .map(p -> ResponseEntity.ok(SlaPolicyLookupResponse.from(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
