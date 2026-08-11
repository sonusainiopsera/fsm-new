package com.fieldservice.catalog.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AssetRequest(
        @NotBlank @Size(max = 100) String assetTag,
        @Size(max = 255) String manufacturer,
        @Size(max = 255) String model,
        @Size(max = 100) String serialNumber,
        @Size(max = 100) String category,
                         Instant installedAt
) {}
