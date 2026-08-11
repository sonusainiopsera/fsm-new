package com.fieldservice.portal.csat;

import com.fieldservice.outbox.payload.CsatResponseRecordedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.access.ScopeUnavailableException;
import com.fieldservice.portal.web.dto.SubmitResponseRequest;
import com.fieldservice.portal.web.dto.SubmitResponseResponse;
import com.fieldservice.portal.web.dto.SurveyRow;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application service for CSAT survey listing and response submission (WO-173).
 *
 * <h3>Access control</h3>
 * All operations are scoped to the authenticated customer's account via
 * {@link CustomerAccessScope}. A survey that belongs to a different account is
 * treated as not found (non-disclosure via {@link ScopeUnavailableException} → 404).
 *
 * <h3>Idempotency</h3>
 * One response per survey is enforced at the entity (unique constraint on survey_id)
 * and at the service layer ({@link CsatAlreadyAnsweredException} → 409).
 *
 * <h3>Event publication</h3>
 * {@code CsatResponseRecorded} is written to the transactional outbox atomically with
 * the response row and survey status update. Comment is excluded from the payload.
 */
@Service
@Transactional(readOnly = true)
public class CsatSurveyService {

    private static final Logger log = LoggerFactory.getLogger(CsatSurveyService.class);

    private final CsatSurveyRepository surveyRepository;
    private final CsatResponseRepository responseRepository;
    private final CustomerAccessScope customerAccessScope;
    private final DomainEventPublisher eventPublisher;

    public CsatSurveyService(
            CsatSurveyRepository surveyRepository,
            CsatResponseRepository responseRepository,
            CustomerAccessScope customerAccessScope,
            DomainEventPublisher eventPublisher) {
        this.surveyRepository     = surveyRepository;
        this.responseRepository   = responseRepository;
        this.customerAccessScope  = customerAccessScope;
        this.eventPublisher       = eventPublisher;
    }

    /**
     * Returns a paginated, account-scoped list of CSAT surveys for the current customer.
     *
     * @param pageQuery page/size parameters (size clamped to 50 by the resolver)
     * @param request   raw HTTP request used to build next/prev links
     * @return paged response envelope
     */
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public PagedResponse<SurveyRow> findSurveys(PageQuery pageQuery, HttpServletRequest request) {
        UUID accountId = customerAccessScope.resolveAccountId();

        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size());
        Page<CsatSurvey> surveyPage =
                surveyRepository.findByAccountIdOrderByIssuedAtDesc(accountId, pageable);

        List<SurveyRow> rows = surveyPage.getContent().stream()
                .map(SurveyRow::from)
                .toList();

        PageMeta meta = PageMeta.of(pageQuery.page(), pageQuery.size(), surveyPage.getTotalElements());
        PageLinks links = buildLinks(request, pageQuery, meta);

        log.debug("csat.surveys.list: accountId={} page={} size={} returned={}",
                accountId, pageQuery.page(), pageQuery.size(), rows.size());
        return PagedResponse.of(rows, meta, links);
    }

    /**
     * Records a customer's response to a CSAT survey.
     *
     * @param surveyId the survey to respond to (must belong to the current customer)
     * @param request  validated response payload
     * @return confirmation with surveyId and submission timestamp
     * @throws ScopeUnavailableException     if the survey is not found or belongs to another account
     * @throws CsatAlreadyAnsweredException  if the survey has already been answered
     * @throws CsatWindowExpiredException    if the response window has closed
     */
    @Transactional
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public SubmitResponseResponse submitResponse(UUID surveyId, SubmitResponseRequest request) {
        UUID accountId = customerAccessScope.resolveAccountId();

        CsatSurvey survey = surveyRepository.findByIdAndAccountId(surveyId, accountId)
                .orElseThrow(() -> new ScopeUnavailableException("Survey not found or access denied: " + surveyId));

        Instant now = Instant.now();

        if (survey.isAnswered()) {
            throw new CsatAlreadyAnsweredException(surveyId);
        }
        if (survey.isExpired(now)) {
            throw new CsatWindowExpiredException(surveyId);
        }

        CsatResponse response = new CsatResponse(
                surveyId,
                request.score(),
                request.npsScore(),
                request.comment(),
                now);
        responseRepository.save(response);

        survey.markAnswered();
        surveyRepository.save(survey);

        publishResponseRecorded(survey, response);

        log.info("csat.response.submitted: surveyId={} workOrderId={} accountId={} score={}",
                surveyId, survey.getWorkOrderId(), accountId, request.score());

        return new SubmitResponseResponse(surveyId, now);
    }

    private void publishResponseRecorded(CsatSurvey survey, CsatResponse response) {
        var payload = new CsatResponseRecordedPayload(
                survey.getId(),
                survey.getWorkOrderId(),
                response.getScore(),
                response.getNpsScore(),
                response.getSubmittedAt());
        eventPublisher.publish(DomainEvent.of(
                CsatResponseRecordedPayload.EVENT_TYPE,
                CsatResponseRecordedPayload.AGGREGATE_TYPE,
                survey.getId(),
                response.getSubmittedAt(),
                MDC.get("traceId"),
                null,
                PiiRedactionUtility.toPayloadMap(payload)));
    }

    private static PageLinks buildLinks(HttpServletRequest request, PageQuery pq, PageMeta meta) {
        int currentPage = pq.page();
        int totalPages  = meta.totalPages();
        String next = (currentPage + 1 < totalPages)
                ? replacePageParam(request, currentPage + 1, pq.size()) : null;
        String prev = currentPage > 0
                ? replacePageParam(request, currentPage - 1, pq.size()) : null;
        return PageLinks.of(next, prev);
    }

    private static String replacePageParam(HttpServletRequest request, int page, int size) {
        return UriComponentsBuilder.fromRequest(request)
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .toUriString();
    }
}
