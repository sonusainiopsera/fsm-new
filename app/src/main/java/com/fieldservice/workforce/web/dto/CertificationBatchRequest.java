package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Batch request for PUT /api/v1/technicians/{id}/certifications. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CertificationBatchRequest(

        @NotNull
        @Size(max = 200, message = "Batch size must not exceed 200 rows")
        @Valid
        List<CertificationLineRequest> items
) {}
