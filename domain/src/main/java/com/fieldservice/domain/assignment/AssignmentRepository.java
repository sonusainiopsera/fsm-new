package com.fieldservice.domain.assignment;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.UUID;

public interface AssignmentRepository extends ScopedRepository<Assignment, UUID> {
}
