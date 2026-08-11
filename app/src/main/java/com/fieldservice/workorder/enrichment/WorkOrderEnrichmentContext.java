package com.fieldservice.workorder.enrichment;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The full grounding context assembled for a single work order.
 *
 * <p>Contains raw (un-redacted) domain objects and free-text fields.
 * Consumers MUST pass this context through the PII redactor before
 * forwarding any content outside the platform trust boundary.
 *
 * @param workOrderId      the target work order
 * @param faultDescription the current fault description (may contain PII)
 * @param asset            the asset this work order is for (may be {@code null} if asset_id is unset)
 * @param site             the site for the work order
 * @param customer         the owning customer (carries PII — used for known-literal redaction)
 * @param priorWorkOrders  recent closed work orders on the same asset, newest first
 */
public record WorkOrderEnrichmentContext(
        UUID workOrderId,
        String faultDescription,
        Asset asset,
        Site site,
        Customer customer,
        List<PriorWorkOrderSummary> priorWorkOrders
) {
    public WorkOrderEnrichmentContext {
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(customer, "customer");
        priorWorkOrders = priorWorkOrders == null ? List.of() : List.copyOf(priorWorkOrders);
    }
}
