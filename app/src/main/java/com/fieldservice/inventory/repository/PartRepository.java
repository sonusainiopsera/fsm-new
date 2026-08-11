package com.fieldservice.inventory.repository;

import com.fieldservice.inventory.domain.Part;
import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PartRepository extends ScopedRepository<Part, UUID> {
}
