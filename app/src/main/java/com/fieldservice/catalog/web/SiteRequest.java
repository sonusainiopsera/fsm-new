package com.fieldservice.catalog.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SiteRequest(
        @NotBlank @Size(max = 20)  String siteCode,
        @NotBlank @Size(max = 255) String displayName,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 20)  String postcode,
                         BigDecimal latitude,
                         BigDecimal longitude,
                         String accessNotes
) {}
