package com.fieldservice.dispatch.web.dto;

import java.util.List;
import java.util.UUID;

public record CandidateDto(
        UUID technicianId,
        String technicianName,
        int rank,
        double score,
        List<FactorDto> factors,
        boolean travelEstimateDegraded
) {}
