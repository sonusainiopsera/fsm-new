package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.UUID;

public interface PartRepository extends ScopedRepository<Part, UUID> {

    java.util.Optional<Part> findByPartNumber(String partNumber);
}
