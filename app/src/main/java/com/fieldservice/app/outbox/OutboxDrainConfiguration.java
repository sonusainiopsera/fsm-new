package com.fieldservice.app.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring scheduling on the {@code worker} profile.
 *
 * <p>The outbox drain poller ({@code OutboxPoller}) and any future periodic sweeps
 * (SLA evaluator, metric refresh) run on the worker deployable only.  The api profile
 * never loads this configuration, so the scheduler thread pool is never started on
 * API replicas.
 *
 * <p>Distributed-lock-guarded sweeps reuse {@link com.fieldservice.platform.outbox.SchedulingLock}
 * and its JDBC implementation without change.
 */
@Configuration
@Profile("worker")
@EnableScheduling
public class OutboxDrainConfiguration {}
