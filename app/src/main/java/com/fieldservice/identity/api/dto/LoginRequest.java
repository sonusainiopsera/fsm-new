package com.fieldservice.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254)
        String email,

        @NotBlank @Size(min = 12, max = 128)
        String password
) {
    @JsonCreator
    public LoginRequest(
            @JsonProperty("email") String email,
            @JsonProperty("password") String password) {
        this(email, password);
    }
}
