package com.fieldservice.domain.technician;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.UUID;

public interface TechnicianRepository extends ScopedRepository<Technician, UUID> {}
