package com.fieldservice.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating or updating a customer.
 * Unknown JSON properties are rejected ({@code @JsonIgnoreProperties(failOnUnknownProperties=true)}).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateCustomerRequest(

        @NotBlank
        @Size(max = 50)
        String accountCode,

        @NotBlank
        @Size(max = 255)
        String legalName,

        @Size(max = 255)
        String primaryContactName,

        @Email
        @Size(max = 320)
        String primaryContactEmail,

        @Size(max = 50)
        String primaryContactPhone,

        @Size(max = 500)
        String billingAddress
) {
}
