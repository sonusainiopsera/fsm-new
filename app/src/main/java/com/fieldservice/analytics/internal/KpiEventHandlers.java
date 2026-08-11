package com.fieldservice.analytics.internal;

import com.fieldservice.outbox.payload.PartsConsumedPayload;
import com.fieldservice.outbox.payload.WorkOrderCreatedPayload;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
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

        WorkOrderStateChangedHandler(
                KpiOutboxConsumer consumer,
                QualityKpiOutboxConsumer qualityConsumer) {
            this.consumer = consumer;
            this.qualityConsumer = qualityConsumer;
        }

        @Override
        public String getSupportedEventType() { return WorkOrderStateChangedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.consume(ctx);
            qualityConsumer.consume(ctx);
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
