package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationTier;

import java.util.UUID;

/**
 * Outbox event payload for data classification create/update events.
 *
 * <p>Contains identifiers and tier only — no lawful-basis notes or handling notes
 * as those may contain internal policy details not suitable for event payloads.
 */
record ClassificationChangedPayload(
        UUID classificationId,
        String entityName,
        String fieldName,
        ClassificationTier tier,
        String changeType
) {
    static final String EVENT_TYPE = "privacy.ClassificationChanged";
    static final String AGGREGATE_TYPE = "DataClassification";
}
