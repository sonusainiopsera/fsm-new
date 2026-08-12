package com.fieldservice.workforce.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** One row in the technician certification batch-upsert payload. */
public record CertificationItemRequest(
        @NotBlank
        String typeCode,

        @Size(max = 100)
        String certificateReference,

        @NotNull @PastOrPresent
        LocalDate issuedOn,

        LocalDate expiresOn,

        @Size(max = 255)
        String issuingBody
) {}
