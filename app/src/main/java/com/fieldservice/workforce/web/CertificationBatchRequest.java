package com.fieldservice.workforce.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Batch upsert request body for PUT /api/v1/technicians/{id}/certifications. */
public record CertificationBatchRequest(
        @NotNull @NotEmpty @Size(max = 200)
        @Valid
        List<CertificationItemRequest> items
) {}
