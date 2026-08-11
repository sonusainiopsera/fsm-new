package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request DTO for creating a technician profile. Unknown properties are rejected. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateTechnicianRequest(

        @NotNull
        UUID userId,

        @NotBlank
        @Size(max = 50)
        String employeeCode,

        @NotBlank
        @Size(max = 255)
        String displayName,

        @Size(max = 30)
        String mobilePhone,

        @Size(max = 50)
        String timezone,

        UUID homeBaseSiteId
) {}
