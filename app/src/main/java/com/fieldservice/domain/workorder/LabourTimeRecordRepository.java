package com.fieldservice.domain.workorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Read/write access to labour time records.
 *
 * <p>Not a scoped repository: labour time records are accessed within the already-scoped
 * work order transaction; row-scope enforcement on the work order prevents unauthorised access.
 */
public interface LabourTimeRecordRepository extends JpaRepository<LabourTimeRecord, UUID> {

    boolean existsByWorkOrderId(UUID workOrderId);
}
