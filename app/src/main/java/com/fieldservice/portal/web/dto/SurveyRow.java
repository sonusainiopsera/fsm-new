package com.fieldservice.portal.web.dto;

import com.fieldservice.portal.csat.CsatDeliveryStatus;
import com.fieldservice.portal.csat.CsatSurvey;
import com.fieldservice.portal.csat.CsatSurveyStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Redacted projection of a CSAT survey row for the portal list endpoint (WO-173).
 *
 * <p>No internal fields (source_event_id, account_id) are exposed.
 */
public record SurveyRow(
        UUID id,
        UUID workOrderId,
        Instant issuedAt,
        Instant expiresAt,
        CsatSurveyStatus status,
        CsatDeliveryStatus deliveryStatus
) {
    public static SurveyRow from(CsatSurvey survey) {
        return new SurveyRow(
                survey.getId(),
                survey.getWorkOrderId(),
                survey.getIssuedAt(),
                survey.getExpiresAt(),
                survey.getStatus(),
                survey.getDeliveryStatus()
        );
    }
}
