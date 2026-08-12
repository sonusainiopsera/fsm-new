package com.fieldservice.portal.csat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link CsatSurvey} domain logic.
 * No Spring context required.
 */
class CsatSurveyLogicTest {

    private static final UUID WO_ID      = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID EVENT_ID   = UUID.randomUUID();

    @Test
    @DisplayName("issue() creates PENDING survey with correct fields")
    void issue_createsPendingSurvey() {
        Instant now      = Instant.now();
        Instant expires  = now.plus(7, ChronoUnit.DAYS);

        CsatSurvey survey = CsatSurvey.issue(UUID.randomUUID(), WO_ID, ACCOUNT_ID,
                EVENT_ID, now, expires);

        assertThat(survey.getStatus()).isEqualTo(CsatSurveyStatus.PENDING);
        assertThat(survey.getDeliveryStatus()).isEqualTo(CsatDeliveryStatus.PENDING);
        assertThat(survey.getWorkOrderId()).isEqualTo(WO_ID);
        assertThat(survey.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(survey.getIssuedAt()).isEqualTo(now);
        assertThat(survey.getExpiresAt()).isEqualTo(expires);
    }

    @Test
    @DisplayName("isExpired() returns false before expiry and true after")
    void isExpired_boundary() {
        Instant now     = Instant.now();
        Instant expires = now.plus(7, ChronoUnit.DAYS);
        CsatSurvey survey = CsatSurvey.issue(UUID.randomUUID(), WO_ID, ACCOUNT_ID,
                EVENT_ID, now, expires);

        assertThat(survey.isExpired(expires.minusMillis(1))).isFalse();
        assertThat(survey.isExpired(expires)).isFalse();
        assertThat(survey.isExpired(expires.plusMillis(1))).isTrue();
    }

    @Test
    @DisplayName("markAnswered() transitions status to ANSWERED")
    void markAnswered_setsAnsweredStatus() {
        CsatSurvey survey = CsatSurvey.issue(UUID.randomUUID(), WO_ID, ACCOUNT_ID,
                EVENT_ID, Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS));
        survey.markAnswered();
        assertThat(survey.getStatus()).isEqualTo(CsatSurveyStatus.ANSWERED);
    }

    @Test
    @DisplayName("markExpired() transitions status to EXPIRED")
    void markExpired_setsExpiredStatus() {
        CsatSurvey survey = CsatSurvey.issue(UUID.randomUUID(), WO_ID, ACCOUNT_ID,
                EVENT_ID, Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS));
        survey.markExpired();
        assertThat(survey.getStatus()).isEqualTo(CsatSurveyStatus.EXPIRED);
    }

    @Test
    @DisplayName("CsatResponse.submit() stores all provided fields")
    void submit_storesAllFields() {
        UUID surveyId = UUID.randomUUID();
        Instant now   = Instant.now();

        CsatResponse response = CsatResponse.submit(UUID.randomUUID(), surveyId,
                (short) 4, (short) 9, "Great service", now);

        assertThat(response.getSurveyId()).isEqualTo(surveyId);
        assertThat(response.getScore()).isEqualTo((short) 4);
        assertThat(response.getNpsScore()).isEqualTo((short) 9);
        assertThat(response.getSubmittedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("CsatResponse.submit() accepts null npsScore and null comment")
    void submit_acceptsNullOptionalFields() {
        CsatResponse response = CsatResponse.submit(UUID.randomUUID(), UUID.randomUUID(),
                (short) 3, null, null, Instant.now());
        assertThat(response.getNpsScore()).isNull();
        assertThat(response.getComment()).isNull();
    }
}
