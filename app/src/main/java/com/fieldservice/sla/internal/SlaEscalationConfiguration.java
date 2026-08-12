package com.fieldservice.sla.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker-profile configuration for SLA escalation.
 *
 * <p>Activates {@link EnableScheduling} so {@link SlaManagerEscalationChecker} runs.
 * EventHandler beans for SlaRiskFlagged/SlaBreached are registered in
 * {@link SlaAlertConfiguration} as composite handlers that chain fanout and escalation,
 * avoiding duplicate-key errors in OutboxPoller's single-handler-per-type map.
 */
@Configuration
@Profile("worker")
@EnableScheduling
class SlaEscalationConfiguration {
}
