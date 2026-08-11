package com.fieldservice.portal.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body returned after a successful CSAT response submission (WO-173).
 */
public record SubmitResponseResponse(
        UUID surveyId,
        Instant submittedAt
) {}
