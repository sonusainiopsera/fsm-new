package com.fieldservice.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Request DTO for creating a site under a customer. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateSiteRequest(

        @Size(max = 50)
        String siteCode,

        @NotBlank
        @Size(max = 255)
        String displayName,

        @Size(max = 500)
        String address,

        @Size(max = 20)
        String postcode,

        @DecimalMin("-90.0") @DecimalMax("90.0")
        BigDecimal latitude,

        @DecimalMin("-180.0") @DecimalMax("180.0")
        BigDecimal longitude,

        String accessNotes
) {
}
