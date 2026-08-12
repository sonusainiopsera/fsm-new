package com.fieldservice.analytics.internal;

import com.fieldservice.outbox.payload.PartsConsumedPayload;
import com.fieldservice.outbox.payload.SlaBreachedPayload;
import com.fieldservice.outbox.payload.SlaPolicyChangedPayload;
import com.fieldservice.outbox.payload.WorkOrderCreatedPayload;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import com.fieldservice.portal.csat.CsatIssuanceConsumer;
import org.springframework.stereotype.Component;

/**
 * Thin EventHandler adapters that route outbox events to {@link KpiOutboxConsumer}.
 *
 * <p>The outbox drain service routes by event_type string, so we need one bean per
 * supported event type. All three delegate directly to the shared consumer logic.
 */
class KpiEventHandlers {

    @Component
    static class WorkOrderCreatedHandler implements EventHandler {
        private final KpiOutboxConsumer consumer;
        WorkOrderCreatedHandler(KpiOutboxConsumer consumer) { this.consumer = consumer; }

        @Override
        public String getSupportedEventType() { return WorkOrderCreatedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.consume(ctx);
        }
    }

    @Component
    static class WorkOrderStateChangedHandler implements EventHandler {
        private final KpiOutboxConsumer consumer;
        private final QualityKpiOutboxConsumer qualityConsumer;
        private final CsatIssuanceConsumer csatIssuanceConsumer;

        WorkOrderStateChangedHandler(
                KpiOutboxConsumer consumer,
                QualityKpiOutboxConsumer qualityConsumer,
                CsatIssuanceConsumer csatIssuanceConsumer) {
            this.consumer             = consumer;
            this.qualityConsumer      = qualityConsumer;
            this.csatIssuanceConsumer = csatIssuanceConsumer;
        }

        @Override
        public String getSupportedEventType() { return WorkOrderStateChangedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.consume(ctx);
            qualityConsumer.consume(ctx);
            csatIssuanceConsumer.consume(ctx);
        }
    }

    // CsatKpiConsumer (analytics.internal) is registered directly as an EventHandler bean
    // for CsatResponseRecorded — no adapter needed here.

    // WO-162: SLA breach events mark SLA compliance and breach-count metrics dirty.
    @Component
    static class SlaBreachedHandler implements EventHandler {
        private final KpiOutboxConsumer consumer;
        SlaBreachedHandler(KpiOutboxConsumer consumer) { this.consumer = consumer; }

        @Override
        public String getSupportedEventType() { return SlaBreachedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.consume(ctx);
        }
    }

    // WO-162: SLA policy changes can alter which work orders are compliant.
    @Component
    static class SlaPolicyChangedHandler implements EventHandler {
        private final KpiOutboxConsumer consumer;
        SlaPolicyChangedHandler(KpiOutboxConsumer consumer) { this.consumer = consumer; }

        @Override
        public String getSupportedEventType() { return SlaPolicyChangedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.consume(ctx);
        }
    }

    @Component
    static class PartsConsumedHandler implements EventHandler {
        private final KpiOutboxConsumer consumer;
        PartsConsumedHandler(KpiOutboxConsumer consumer) { this.consumer = consumer; }

        @Override
        public String getSupportedEventType() { return PartsConsumedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.consume(ctx);
        }
    }
}
