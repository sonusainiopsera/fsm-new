package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** One certification row in a batch upsert request. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CertificationLineRequest(

        @NotBlank
        @Size(max = 50)
        String typeCode,

        @Size(max = 100)
        String certificateReference,

        @NotNull
        LocalDate issuedOn,

        LocalDate expiresOn,

        @Size(max = 255)
        String issuingBody
) {}
