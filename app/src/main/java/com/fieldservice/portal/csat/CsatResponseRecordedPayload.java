package com.fieldservice.portal.csat;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event payload emitted when a customer submits a CSAT response.
 *
 * <p>Carries only identifiers — never PII (the encrypted comment stays in the database).
 * The analytics read model consumes this event to refresh rolling CSAT metrics.
 */
public record CsatResponseRecordedPayload(
        UUID surveyId,
        UUID workOrderId,
        UUID accountId,
        short score,
        Short npsScore,
        Instant submittedAt) {}
