package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for the {@code CsatResponseRecorded} event emitted when a customer submits a
 * CSAT survey response (WO-173).
 *
 * <p>The comment field is intentionally excluded — it is PII, encrypted at rest, and
 * must not propagate through the outbox. Only score, NPS, and timestamps are carried.
 */
public record CsatResponseRecordedPayload(
        UUID surveyId,
        UUID workOrderId,
        short score,
        Short npsScore,
        Instant submittedAt
) {
    /** Stable event type identifier. */
    public static final String EVENT_TYPE = "CsatResponseRecorded";

    /** Stable aggregate type identifier. */
    public static final String AGGREGATE_TYPE = "CsatSurvey";
}
