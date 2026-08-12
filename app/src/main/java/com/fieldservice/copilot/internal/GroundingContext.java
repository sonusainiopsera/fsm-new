package com.fieldservice.copilot.internal;

import com.fieldservice.workorder.enrichment.PriorServiceEntry;
import com.fieldservice.workorder.enrichment.WorkOrderContextData;

import java.util.List;
import java.util.UUID;

/**
 * Raw enrichment context assembled for a work order, before PII redaction.
 *
 * <p>Package-private: this type can only be created by {@link GroundingContextRetriever}.
 * Because no external code can obtain a raw {@code GroundingContext}, the only path from
 * context to an {@link com.fieldservice.aigateway.api.AiCompletionRequest} is through
 * {@link PromptAssembler}, which always invokes the redactor — making redaction unconditional.
 */
record GroundingContext(
        UUID workOrderId,
        String workOrderReference,
        UUID assetId,
        String assetModel,
        String assetManufacturer,
        String assetSerialNumber,
        String assetCategory,
        String faultDescription,
        String faultCode,
        String faultCategory,
        String siteName,
        String siteAddressLine1,
        String siteCity,
        String sitePostcode,
        String customerName,
        String customerLegalName,
        String customerPrimaryContactName,
        String customerContactEmail,
        String customerContactPhone,
        String customerPrimaryContactEmail,
        String customerPrimaryContactPhone,
        String customerBillingAddress,
        List<PriorServiceEntry> priorServiceHistory) {

    static GroundingContext from(WorkOrderContextData d) {
        return new GroundingContext(
                d.workOrderId(), d.workOrderReference(),
                d.assetId(), d.assetModel(), d.assetManufacturer(),
                d.assetSerialNumber(), d.assetCategory(),
                d.faultDescription(), d.faultCode(), d.faultCategory(),
                d.siteName(), d.siteAddressLine1(), d.siteCity(), d.sitePostcode(),
                d.customerName(), d.customerLegalName(), d.customerPrimaryContactName(),
                d.customerContactEmail(), d.customerContactPhone(),
                d.customerPrimaryContactEmail(), d.customerPrimaryContactPhone(),
                d.customerBillingAddress(),
                d.priorServiceHistory());
    }
}
