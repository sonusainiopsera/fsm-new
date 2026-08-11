package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One row in a batch skill-upsert request. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SkillLineRequest(

        @NotBlank
        @Size(max = 50)
        String skillCode,

        @NotNull
        @Pattern(regexp = "NOVICE|COMPETENT|PROFICIENT|EXPERT",
                message = "must be one of NOVICE, COMPETENT, PROFICIENT, EXPERT")
        String proficiency,

        @Min(0)
        Integer yearsExperience
) {}
