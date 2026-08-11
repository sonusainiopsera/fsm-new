package com.fieldservice.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Request DTO for creating an asset under a site. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateAssetRequest(

        @NotBlank
        @Size(max = 100)
        String assetTag,

        @Size(max = 100)
        String manufacturer,

        @Size(max = 255)
        String model,

        @Size(max = 100)
        String serialNumber,

        @Size(max = 100)
        String category,

        LocalDate installedOn
) {
}
