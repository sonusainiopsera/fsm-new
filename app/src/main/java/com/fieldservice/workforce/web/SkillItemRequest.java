package com.fieldservice.workforce.web;

import com.fieldservice.workforce.internal.Proficiency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SkillItemRequest(
        @NotBlank String skillCode,
        @NotNull Proficiency proficiency,
        @DecimalMin("0") BigDecimal yearsExperience
) {}
