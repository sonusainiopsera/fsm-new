package com.fieldservice.catalog.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CustomerRequest(
        @NotBlank @Size(max = 20)  String accountCode,
        @NotBlank @Size(max = 255) String legalName,
        @Size(max = 255) String primaryContactName,
        @Size(max = 255) String primaryContactEmail,
        @Size(max = 50)  String primaryContactPhone,
                         String billingAddress
) {}
