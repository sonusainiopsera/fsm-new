package com.fieldservice.portal.csat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.outbox.EventHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Creates a CSAT survey on work order closure (WO-173).
 *
 * <p>Called from {@code KpiEventHandlers.WorkOrderStateChangedHandler} — NOT registered
 * as a top-level {@link com.fieldservice.platform.outbox.EventHandler} to avoid a
 * duplicate event-type registration conflict in {@code OutboxDrainService}.
 *
 * <h3>Idempotency</h3>
 * The survey is inserted with {@code source_event_id = ctx.eventId()}. A unique
 * constraint violation (same event replayed) is caught and treated as success —
 * exactly one survey exists regardless of how many times the event is delivered.
 *
 * <h3>Scope</h3>
 * Only {@code toState=CLOSED} transitions trigger issuance. Other state transitions
 * through the same event type are ignored.
 *
 * <h3>Account resolution</h3>
 * The account id is read directly from {@code work_order.customer_id} via
 * a JDBC query rather than through {@link com.fieldservice.portal.access.CustomerAccessScope},
 * which requires a request context. This consumer runs inside the outbox drain (no request).
 */
@Component
public class CsatIssuanceConsumer {

    private static final Logger log = LoggerFactory.getLogger(CsatIssuanceConsumer.class);

    @Value("${app.portal.csat.response-window-days:30}")
    int responseWindowDays;

    private final CsatSurveyRepository surveyRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CsatIssuanceConsumer(CsatSurveyRepository surveyRepository,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper) {
        this.surveyRepository = surveyRepository;
        this.jdbcTemplate     = jdbcTemplate;
        this.objectMapper     = objectMapper;
    }

    /**
     * Processes a {@code WorkOrderStateChanged} event; issues a survey if it's a closure.
     */
    public void consume(EventHandlerContext ctx) {
        if (!WorkOrderStateChangedPayload.EVENT_TYPE.equals(ctx.eventType())) return;

        WorkOrderStateChangedPayload payload;
        try {
            payload = objectMapper.readValue(ctx.payloadJson(), WorkOrderStateChangedPayload.class);
        } catch (Exception ex) {
            log.error("csat.issuance.parse_error: eventId={} — {}", ctx.eventId(), ex.getMessage());
            throw new RuntimeException("CsatIssuanceConsumer parse failed for eventId=" + ctx.eventId(), ex);
        }

        if (!"CLOSED".equals(payload.toState())) {
            return;
        }

        UUID workOrderId = payload.workOrderId();
        UUID accountId   = resolveAccountId(workOrderId);
        if (accountId == null) {
            log.warn("csat.issuance.no_account: workOrderId={} eventId={}", workOrderId, ctx.eventId());
            return;
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds((long) responseWindowDays * 86_400);

        CsatSurvey survey = new CsatSurvey(workOrderId, accountId, ctx.eventId(), now, expiresAt);
        survey.markDeliveryInApp();

        try {
            surveyRepository.save(survey);
            log.info("csat.issuance.issued: surveyId={} workOrderId={} accountId={} expires={}",
                    survey.getId(), workOrderId, accountId, expiresAt);
        } catch (DataIntegrityViolationException ex) {
            // Idempotent — a unique-constraint violation means this event was already processed.
            log.debug("csat.issuance.duplicate: eventId={} workOrderId={} — already issued",
                    ctx.eventId(), workOrderId);
        }
    }

    private UUID resolveAccountId(UUID workOrderId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT customer_id FROM work_order WHERE id = ?",
                    UUID.class, workOrderId);
        } catch (Exception ex) {
            log.warn("csat.issuance.account_lookup_failed: workOrderId={} — {}",
                    workOrderId, ex.getMessage());
            return null;
        }
    }
}
