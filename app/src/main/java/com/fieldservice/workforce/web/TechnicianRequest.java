package com.fieldservice.workforce.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TechnicianRequest(
        @NotNull UUID userId,
        @Size(max = 50) String employeeCode,
        @NotBlank @Size(max = 255) String displayName,
        @Size(max = 100) String timezone,
        String mobilePhone,
        UUID homeBaseSiteId
) {}
