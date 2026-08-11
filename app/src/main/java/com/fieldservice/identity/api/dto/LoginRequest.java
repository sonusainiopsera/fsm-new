package com.fieldservice.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credential submission for the login endpoint.
 *
 * <p>Password is length-capped at 128 characters by Bean Validation before it
 * reaches the BCrypt encoder, preventing CPU exhaustion via artificially long inputs.
 */
public record LoginRequest(

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        @Size(max = 254, message = "must not exceed 254 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
        String password
) {}
