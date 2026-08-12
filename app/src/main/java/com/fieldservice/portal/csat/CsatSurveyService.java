package com.fieldservice.portal.csat;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.access.ScopeUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Application service for CSAT survey listing and response submission.
 *
 * <p>All methods enforce {@code CUSTOMER} role via {@code @PreAuthorize}.
 * Survey ownership is checked before any mutation — a foreign or unknown survey ID
 * returns 404 via {@link ScopeUnavailableException} (non-disclosing, per the portal
 * disclosure policy).
 */
@Service
public class CsatSurveyService {

    private static final Logger log = LoggerFactory.getLogger(CsatSurveyService.class);

    private final CsatSurveyRepository    surveyRepository;
    private final CsatResponseRepository  responseRepository;
    private final CustomerAccessScope     customerAccessScope;
    private final DomainEventPublisher    eventPublisher;
    private final Clock                   clock;

    public CsatSurveyService(CsatSurveyRepository   surveyRepository,
                              CsatResponseRepository responseRepository,
                              CustomerAccessScope    customerAccessScope,
                              DomainEventPublisher   eventPublisher,
                              Clock                  clock) {
        this.surveyRepository   = surveyRepository;
        this.responseRepository = responseRepository;
        this.customerAccessScope = customerAccessScope;
        this.eventPublisher     = eventPublisher;
        this.clock              = clock;
    }

    /**
     * Returns the authenticated account's surveys, newest first.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('CUSTOMER')")
    public Page<CsatSurvey> findSurveys(Pageable pageable) {
        UUID accountId = customerAccessScope.resolveAccountId();
        return surveyRepository.findByAccountIdOrderByIssuedAtDesc(accountId, pageable);
    }

    /**
     * Records a CSAT response and marks the survey as answered.
     *
     * @throws ScopeUnavailableException   survey not found or owned by a different account (→ 404)
     * @throws CsatAlreadyAnsweredException survey already answered (→ 409)
     * @throws CsatWindowExpiredException   response window has closed (→ 422)
     */
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public CsatResponse submitResponse(UUID surveyId, CsatResponseRequest request) {
        UUID accountId = customerAccessScope.resolveAccountId();

        CsatSurvey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> new ScopeUnavailableException("survey not found"));

        if (!survey.getAccountId().equals(accountId)) {
            throw new ScopeUnavailableException("survey not accessible");
        }

        if (survey.getStatus() == CsatSurveyStatus.ANSWERED) {
            throw new CsatAlreadyAnsweredException(surveyId);
        }

        Instant now = clock.instant();
        if (survey.isExpired(now)) {
            survey.markExpired();
            throw new CsatWindowExpiredException(surveyId);
        }

        Short nps = request.npsScore() != null ? request.npsScore().shortValue() : null;
        CsatResponse response = CsatResponse.submit(
                UuidV7.generate(), surveyId,
                request.score().shortValue(), nps,
                request.comment(), now);
        responseRepository.save(response);
        survey.markAnswered();

        log.info("csat_response_submitted survey_id={} account_id={}", surveyId, accountId);

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "CSAT_RESPONSE_RECORDED",
                "CSAT_SURVEY",
                surveyId,
                now,
                MDC.get("traceId"),
                null,
                new CsatResponseRecordedPayload(
                        surveyId, survey.getWorkOrderId(), accountId,
                        response.getScore(), response.getNpsScore(), now)));

        return response;
    }
}
