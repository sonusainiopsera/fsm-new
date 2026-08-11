package com.fieldservice.privacy.internal;

import com.fieldservice.platform.outbox.SchedulingLock;
import com.fieldservice.privacy.api.RetentionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scheduled purge sweep — runs on the {@code worker} Spring profile only.
 *
 * <p>Execution is controlled by two independent guards:
 * <ol>
 *   <li>The {@code privacy.purge.execution-enabled} master kill-switch (defaults to
 *       {@code false}; must be explicitly enabled after Q7 DPO ratification).</li>
 *   <li>A distributed lease via {@link SchedulingLock} so exactly one worker replica
 *       executes the sweep regardless of replica count.</li>
 * </ol>
 *
 * <p>Per-run behaviour:
 * <ul>
 *   <li>Processes only {@code retention_policy} rows where {@code ratified=true},
 *       {@code enabled=true} and {@code legal_hold=false}.</li>
 *   <li>Skips rows with no registered {@link RetentionTarget}.</li>
 *   <li>Enforces the 1-year audit-retention floor for {@code AUDIT_RECORDS}.</li>
 *   <li>Deletes eligible rows in bounded batches; stops when the per-run time
 *       budget is exhausted.</li>
 *   <li>Writes an immutable {@link PurgeRun} record for every execution, including
 *       zero-disposal runs.</li>
 * </ul>
 */
@Component
@Profile("worker")
public class PurgeSweepJob {

    private static final Logger log = LoggerFactory.getLogger(PurgeSweepJob.class);
    private static final String LOCK_NAME = "purge-sweep";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    static final String AUDIT_CATEGORY = "AUDIT_RECORDS";

    private final SchedulingLock schedulingLock;
    private final RetentionPolicyRepository policyRepository;
    private final PurgeRunRepository purgeRunRepository;
    private final Map<String, RetentionTarget> targetsByCategory;
    private final RetentionPolicyProperties properties;
    private final Clock clock;

    public PurgeSweepJob(SchedulingLock schedulingLock,
                         RetentionPolicyRepository policyRepository,
                         PurgeRunRepository purgeRunRepository,
                         List<RetentionTarget> retentionTargets,
                         RetentionPolicyProperties properties,
                         Clock clock) {
        this.schedulingLock = schedulingLock;
        this.policyRepository = policyRepository;
        this.purgeRunRepository = purgeRunRepository;
        this.targetsByCategory = retentionTargets.stream()
                .collect(Collectors.toMap(RetentionTarget::getDataCategory, Function.identity()));
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${privacy.purge.cron:0 0 2 * * *}")
    public void runScheduled() {
        if (!properties.isExecutionEnabled()) {
            log.debug("purge.sweep: skipped — privacy.purge.execution-enabled=false");
            return;
        }
        schedulingLock.runIfLeader(LOCK_NAME, LEASE_DURATION, this::executeSweep);
    }

    void executeSweep() {
        List<RetentionPolicy> activePolicies =
                policyRepository.findByRatifiedTrueAndEnabledTrueAndLegalHoldFalse();

        log.info("purge.sweep: starting — {} active policies", activePolicies.size());
        Instant sweepStart = Instant.now(clock);
        Duration timeBudget = Duration.ofSeconds(properties.getTimeBudgetSeconds());

        for (RetentionPolicy policy : activePolicies) {
            if (Duration.between(sweepStart, Instant.now(clock)).compareTo(timeBudget) >= 0) {
                log.info("purge.sweep: time budget exhausted after category={}", policy.getDataCategory());
                break;
            }
            processPolicy(policy, sweepStart, timeBudget);
        }

        log.info("purge.sweep: finished");
    }

    @Transactional
    void processPolicy(RetentionPolicy policy, Instant sweepStart, Duration timeBudget) {
        String category = policy.getDataCategory();
        Instant startedAt = Instant.now(clock);
        Instant cutoff = RetentionPolicyServiceImpl.computeCutoff(
                policy.getPeriodValue(), policy.getPeriodUnit(),
                properties.getZoneId(), startedAt);

        List<String> skipReasons = new ArrayList<>();

        if (violatesAuditFloor(category, policy.getPeriodValue(), policy.getPeriodUnit())) {
            skipReasons.add("AUDIT_FLOOR_GUARD");
            log.warn("purge.sweep: AUDIT_FLOOR_GUARD for category={} — policy shorter than 1-year floor", category);
        }

        RetentionTarget target = targetsByCategory.get(category);
        if (target == null) {
            skipReasons.add("NO_TARGET_REGISTERED");
            log.warn("purge.sweep: no RetentionTarget registered for category={}", category);
        }

        if (!skipReasons.isEmpty()) {
            PurgeRun skipped = new PurgeRun(category, startedAt, Instant.now(clock), cutoff,
                    0L, 0L, 0L, toJson(skipReasons), "SKIPPED", null);
            purgeRunRepository.save(skipped);
            return;
        }

        long rowsExamined = 0;
        long rowsDisposed = 0;
        long rowsSkipped = 0;
        String outcome = "SUCCESS";
        String errorCode = null;

        try {
            while (Duration.between(sweepStart, Instant.now(clock)).compareTo(timeBudget) < 0) {
                List<UUID> page = target.pageEligibleIds(cutoff, properties.getBatchSize());
                if (page.isEmpty()) {
                    break;
                }
                rowsExamined += page.size();
                try {
                    target.disposeBatch(page);
                    rowsDisposed += page.size();
                } catch (Exception batchEx) {
                    rowsSkipped += page.size();
                    skipReasons.add("BATCH_ERROR: " + batchEx.getMessage());
                    outcome = "PARTIAL";
                    errorCode = batchEx.getClass().getSimpleName();
                    log.error("purge.sweep: batch error category={} batchSize={}", category, page.size(), batchEx);
                }
            }
            if (Duration.between(sweepStart, Instant.now(clock)).compareTo(timeBudget) >= 0
                    && rowsExamined > 0) {
                outcome = "TIME_BUDGET_EXCEEDED";
            }
        } catch (Exception ex) {
            outcome = "FAILED";
            errorCode = ex.getClass().getSimpleName();
            log.error("purge.sweep: unexpected error category={}", category, ex);
        }

        Instant finishedAt = Instant.now(clock);
        PurgeRun run = new PurgeRun(category, startedAt, finishedAt, cutoff,
                rowsExamined, rowsDisposed, rowsSkipped,
                skipReasons.isEmpty() ? null : toJson(skipReasons),
                outcome, errorCode);
        purgeRunRepository.save(run);

        log.info("purge.sweep: category={} outcome={} examined={} disposed={} skipped={}",
                category, outcome, rowsExamined, rowsDisposed, rowsSkipped);
    }

    boolean violatesAuditFloor(String dataCategory, int periodValue, String periodUnit) {
        if (!AUDIT_CATEGORY.equals(dataCategory)) {
            return false;
        }
        ZoneId zone = ZoneId.of(properties.getZoneId());
        ZonedDateTime nowZdt = Instant.now(clock).atZone(zone);
        ZonedDateTime proposedCutoff = switch (periodUnit) {
            case "DAYS" -> nowZdt.minusDays(periodValue);
            case "MONTHS" -> nowZdt.minusMonths(periodValue);
            case "YEARS" -> nowZdt.minusYears(periodValue);
            default -> nowZdt.minusDays(periodValue);
        };
        ZonedDateTime auditFloorCutoff = nowZdt.minusYears(1);
        return proposedCutoff.isAfter(auditFloorCutoff);
    }

    private static String toJson(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return null;
        return "[" + reasons.stream()
                .map(r -> "\"" + r.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",")) + "]";
    }
}
