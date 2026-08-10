package com.fieldservice.domain.workorder;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.UUID;

public interface WorkOrderRepository extends ScopedRepository<WorkOrder, UUID> {
}
