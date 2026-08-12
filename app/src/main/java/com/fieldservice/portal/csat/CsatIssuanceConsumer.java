package com.fieldservice.portal.csat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.workorder.application.WorkOrderTransitionPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Outbox consumer that issues exactly one CSAT survey per closed work order.
 *
 * <p>Idempotency: a pre-check on {@code source_event_id} and {@code work_order_id} prevents
 * duplicate issuance under at-least-once delivery. The unique constraints in the schema are
 * the second line of defence.
 *
 * <p>Decoupling: survey issuance is never inside the work-order closure transaction. Closure
 * succeeds and commits independently; this consumer reacts asynchronously via the outbox poller.
 * A failing consumer never blocks closure.
 *
 * <p>Registered as an {@link EventHandler} bean via {@link CsatIssuanceConfiguration}.
 */
@Component
class CsatIssuanceConsumer {

    private static final Logger log = LoggerFactory.getLogger(CsatIssuanceConsumer.class);

    static final String EVENT_TYPE  = "WORK_ORDER_STATE_CHANGED";
    static final String TARGET_STATE = "CLOSED";

    private final CsatSurveyRepository     surveyRepository;
    private final CsatProperties           properties;
    private final CsatSurveyDeliveryService deliveryService;
    private final ObjectMapper             objectMapper;
    private final Clock                    clock;

    CsatIssuanceConsumer(CsatSurveyRepository     surveyRepository,
                         CsatProperties            properties,
                         CsatSurveyDeliveryService deliveryService,
                         ObjectMapper              objectMapper,
                         Clock                     clock) {
        this.surveyRepository  = surveyRepository;
        this.properties        = properties;
        this.deliveryService   = deliveryService;
        this.objectMapper      = objectMapper;
        this.clock             = clock;
    }

    /**
     * Handles a {@code WORK_ORDER_STATE_CHANGED} event, issuing a survey when the
     * transition is to {@code CLOSED} and no survey already exists.
     *
     * <p>Must be called within an active transaction (MANDATORY propagation) — the outbox
     * poller's claim transaction satisfies this requirement.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void consume(DomainEvent event) throws Exception {
        WorkOrderTransitionPayload payload = parsePayload(event);
        if (!TARGET_STATE.equals(payload.toState())) {
            return;
        }

        UUID workOrderId   = payload.workOrderId();
        UUID sourceEventId = event.eventId();

        if (surveyRepository.existsBySourceEventId(sourceEventId)) {
            log.debug("csat_already_issued_for_event source_event_id={}", sourceEventId);
            return;
        }
        if (surveyRepository.existsByWorkOrderId(workOrderId)) {
            log.debug("csat_already_issued_for_work_order work_order_id={}", workOrderId);
            return;
        }

        UUID accountId = surveyRepository.findAccountIdByWorkOrderId(workOrderId).orElse(null);
        if (accountId == null) {
            log.warn("csat_work_order_not_found work_order_id={} source_event_id={}",
                    workOrderId, sourceEventId);
            return;
        }

        Instant issuedAt  = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.responseWindowDays(), ChronoUnit.DAYS);

        CsatSurvey survey = CsatSurvey.issue(
                UuidV7.generate(), workOrderId, accountId, sourceEventId, issuedAt, expiresAt);
        surveyRepository.save(survey);

        log.info("csat_survey_issued survey_id={} work_order_id={} account_id={} expires_at={}",
                survey.getId(), workOrderId, accountId, expiresAt);

        deliveryService.attemptDelivery(survey);
    }

    private WorkOrderTransitionPayload parsePayload(DomainEvent event) throws Exception {
        if (event.payload() instanceof WorkOrderTransitionPayload p) {
            return p;
        }
        return objectMapper.convertValue(event.payload(), WorkOrderTransitionPayload.class);
    }

    static EventHandler asEventHandler(CsatIssuanceConsumer consumer) {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_TYPE; }
            @Override public void handle(DomainEvent event) throws Exception { consumer.consume(event); }
        };
    }
}
