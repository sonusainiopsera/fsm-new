package com.fieldservice.sla;

import com.fieldservice.sla.web.dto.CreateSlaPolicyRequest;
import com.fieldservice.sla.web.dto.UpdateSlaPolicyRequest;

import java.util.List;
import java.util.UUID;

/**
 * Public admin interface for SLA policy read and update operations.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Publish an {@code SlaPolicyChanged} outbox event on every successful write.</li>
 *   <li>Evict the {@code sla-policy} Caffeine cache so the next deadline derivation
 *       sees the new values without a restart.</li>
 *   <li>Return 409 on optimistic-lock conflict (version mismatch).</li>
 * </ul>
 *
 * <p>This interface lives in the public {@code sla} package so the web controller
 * can reference it without depending on sla internals, satisfying the ArchUnit rule
 * that no workorder or web layer code depends on {@code sla.internal}.
 */
public interface SlaPolicyAdminService {

    /** Returns all SLA policies (active and superseded) ordered by priority then effective_from. */
    List<SlaPolicy> listPolicies();

    /** Creates a new SLA policy row. */
    SlaPolicy createPolicy(CreateSlaPolicyRequest req);

    /**
     * Directly updates the named SLA policy row in place.
     *
     * <p>Unlike supersede, this modifies the existing row so the UUID is stable across
     * edits. Optimistic locking prevents silent concurrent overwrites.
     *
     * @param id  the SLA policy UUID
     * @param req the update payload including {@code version} for optimistic locking
     * @return the updated policy as a public DTO
     * @throws com.fieldservice.platform.exception.NotFoundException if {@code id} not found
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException on version conflict
     */
    SlaPolicy updatePolicy(UUID id, UpdateSlaPolicyRequest req);
}
