package com.fieldservice.workforce.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SkillRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "[A-Z0-9_]+") String code,
        @NotBlank @Size(max = 255) String displayName
) {}
