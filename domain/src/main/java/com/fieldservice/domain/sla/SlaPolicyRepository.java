package com.fieldservice.domain.sla;

import com.fieldservice.platform.persistence.ScopedRepository;
import com.fieldservice.platform.security.UnscopedRead;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@UnscopedRead(justification = "SLA policy is read-only configuration data with no per-user scope — all roles need the same response/resolution targets to calculate deadlines. No PII or tenant-isolated data exists in sla_policy.")
public interface SlaPolicyRepository extends ScopedRepository<SlaPolicy, UUID> {

    Optional<SlaPolicy> findTopByPriorityAndEffectiveFromLessThanEqualAndEffectiveToIsNullOrderByEffectiveFromDesc(
            String priority, Instant asOf);

    List<SlaPolicy> findByPriorityOrderByEffectiveFromDesc(String priority);
}
