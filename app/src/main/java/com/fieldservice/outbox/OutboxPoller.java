package com.fieldservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled outbox drain loop — active only on the {@code worker} profile.
 *
 * <p>Delegates actual claim-and-dispatch logic to {@link OutboxDrainService} so that
 * integration tests can call the service directly without activating the scheduler.
 */
@Component
@Profile("worker")
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxDrainService drainService;
    private final OutboxPollerProperties properties;

    public OutboxPoller(OutboxDrainService drainService, OutboxPollerProperties properties) {
        this.drainService = drainService;
        this.properties = properties;
    }

    /**
     * Runs every {@code app.outbox.poller.poll-interval-ms} milliseconds (default 500ms).
     * Claims up to {@code batch-size} events per pass with {@code FOR UPDATE SKIP LOCKED}.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poller.poll-interval-ms:500}")
    public void poll() {
        try {
            int processed = drainService.drainBatch(properties.getBatchSize());
            if (processed > 0) {
                log.debug("Outbox poller drained {} event(s)", processed);
            }
        } catch (Exception e) {
            log.error("Outbox poll pass failed — will retry on next tick", e);
        }
    }
}
