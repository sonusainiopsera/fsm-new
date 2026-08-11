package com.fieldservice.domain.technician;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TechnicianCertificationRepository extends ScopedRepository<TechnicianCertification, UUID> {

    List<TechnicianCertification> findByTechnicianIdAndExpiresAtAfter(UUID technicianId, Instant now);
}
