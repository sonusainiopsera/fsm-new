package com.fieldservice.workorder.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkOrderCreationRequest(
        @NotBlank @Size(max = 50)
        String reference,

        @NotBlank @Size(max = 20)
        String priority,

        @NotNull
        UUID siteId,

        UUID assignedTechnicianId,

        @Size(max = 4000)
        String description
) {}
