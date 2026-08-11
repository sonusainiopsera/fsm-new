package com.fieldservice.workorder.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fieldservice.workorder.domain.WorkOrderPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} enforces mass-assignment protection:
 * fields like {@code state}, {@code deadlines}, {@code assignee}, or {@code version} cannot
 * be injected by a caller even if Jackson can technically bind them.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkOrderCreationRequest(
        @NotNull
        UUID customerId,

        @NotNull
        UUID siteId,

        UUID assetId,

        @NotBlank @Size(min = 10, max = 4000)
        String faultDescription,

        @NotNull
        WorkOrderPriority priority,

        List<String> requiredCertificationCodes,

        @Valid
        List<ExpectedPartItem> expectedParts
) {

    /** A single part expected to be consumed on this work order. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExpectedPartItem(
            @NotNull UUID partId,
            @Min(1)  int  quantity
    ) {}
}
