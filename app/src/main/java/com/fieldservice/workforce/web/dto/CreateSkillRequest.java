package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request body for POST /api/v1/skills. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateSkillRequest(

        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "[A-Z0-9_\\-]+", message = "Skill code must be uppercase alphanumeric with _ or -")
        String code,

        @NotBlank
        @Size(max = 255)
        String displayName
) {}
