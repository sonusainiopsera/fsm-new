package com.fieldservice.aiaudit.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Daily job that physically deletes AI interaction records past their {@code retain_until} timestamp.
 *
 * <p>Runs only on the {@code worker} profile. Uses a PostgreSQL advisory lock to prevent
 * concurrent execution across nodes. Deletion is bounded to {@link AiAuditProperties#getPurgeBatchSize()}
 * rows per invocation — long-running purges are safe to interrupt and will be retried daily.
 *
 * <p>Physical deletion (not soft-delete) is required by the data retention and purge policy.
 */
@Component
@Profile("worker")
class AiInteractionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionPurgeJob.class);

    /** Stable advisory lock key — "AIPR" encoded as long. */
    private static final long ADVISORY_LOCK_KEY = 0x41495052L;

    private final AiInteractionRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final AiAuditProperties props;

    AiInteractionPurgeJob(AiInteractionRepository repository,
                           JdbcTemplate jdbcTemplate,
                           AiAuditProperties props) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    /** Purges expired records. Runs daily at 03:00 UTC. */
    @Scheduled(cron = "${app.ai.audit.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpired() {
        if (!tryAcquireLock()) {
            log.debug("ai_interaction purge skipped — another node holds the advisory lock");
            return;
        }
        try {
            Instant now = Instant.now();
            int deleted = repository.deleteExpiredBatch(now, props.getPurgeBatchSize());
            log.info("ai_interaction purge complete deletedRows={} batchSize={}",
                    deleted, props.getPurgeBatchSize());
        } catch (Exception e) {
            log.error("ai_interaction purge failed — will retry on next scheduled run error={}", e.getMessage(), e);
        } finally {
            releaseLock();
        }
    }

    private boolean tryAcquireLock() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
        } catch (Exception e) {
            log.warn("Failed to release ai_interaction purge advisory lock: {}", e.getMessage());
        }
    }
}
