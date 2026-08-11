package com.fieldservice.idempotency;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's task scheduling on the {@code worker} profile so that
 * {@link IdempotencyKeyPurgeJob} and any future scheduled jobs can run.
 */
@Configuration
@Profile("worker")
@EnableScheduling
public class IdempotencyWorkerConfig {
}
