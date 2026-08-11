package com.fieldservice.inventory.application;

import com.fieldservice.domain.inventory.WorkOrderPartConsumptionRepository;
import com.fieldservice.inventory.api.WorkOrderConsumptionQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
class WorkOrderConsumptionQueryPortImpl implements WorkOrderConsumptionQueryPort {

    private final WorkOrderPartConsumptionRepository consumptionRepository;

    WorkOrderConsumptionQueryPortImpl(WorkOrderPartConsumptionRepository consumptionRepository) {
        this.consumptionRepository = consumptionRepository;
    }

    @Override
    public boolean hasUnreconciledConsumption(UUID workOrderId) {
        return consumptionRepository.existsByWorkOrderIdAndReconciledFalse(workOrderId);
    }
}
