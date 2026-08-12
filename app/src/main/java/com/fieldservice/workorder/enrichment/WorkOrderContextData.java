package com.fieldservice.workorder.enrichment;

import java.util.List;
import java.util.UUID;

/**
 * Enrichment context for a work order, comprising asset identity, fault details,
 * site/customer information (for PII redaction), and prior service history on the same asset.
 */
public record WorkOrderContextData(
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
}
