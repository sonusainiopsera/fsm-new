package com.fieldservice.privacy.internal;

import com.fieldservice.platform.outbox.JdbcSchedulingLock;
import com.fieldservice.privacy.api.RetentionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scheduled retention purge sweep running on the {@code worker} Spring profile only.
 *
 * <p>Execution is disabled by default ({@code app.privacy.purge.execution-enabled=false})
 * until Q7 retention periods are ratified by the DPO.  When disabled, the job writes a
 * {@link PurgeRunEntity} with outcome {@code SKIPPED_EXECUTION_DISABLED} for every enabled
 * and ratified policy so that an auditor can see the sweep ran but was not yet executing.
 *
 * <p>When enabled, the job processes only policies where both {@code ratified} and
 * {@code enabled} are true, skips any row with {@code legal_hold=true}, refuses to delete
 * audit revisions younger than the one-year floor, and processes each category in bounded
 * batches within the configured per-run time budget.
 *
 * <p>The distributed lock ({@link JdbcSchedulingLock}) ensures exactly one worker replica
 * executes the sweep per lease window regardless of replica count.
 */
@Component
@Profile("worker")
class PurgeSweepJob {

    private static final Logger log = LoggerFactory.getLogger(PurgeSweepJob.class);
    private static final String LOCK_NAME = "retention-purge-sweep";

    private final RetentionPolicyRepository    policyRepository;
    private final PurgeRunRepository           purgeRunRepository;
    private final RetentionCutoffCalculator    cutoffCalculator;
    private final JdbcSchedulingLock           schedulingLock;
    private final RetentionProperties          properties;
    private final Clock                        clock;
    private final PlatformTransactionManager   txManager;
    private final Map<String, RetentionTarget> targetMap;

    PurgeSweepJob(RetentionPolicyRepository    policyRepository,
                  PurgeRunRepository           purgeRunRepository,
                  RetentionCutoffCalculator    cutoffCalculator,
                  JdbcSchedulingLock           schedulingLock,
                  RetentionProperties          properties,
                  Clock                        clock,
                  PlatformTransactionManager   txManager,
                  List<RetentionTarget>        targets) {
        this.policyRepository = policyRepository;
        this.purgeRunRepository = purgeRunRepository;
        this.cutoffCalculator  = cutoffCalculator;
        this.schedulingLock    = schedulingLock;
        this.properties        = properties;
        this.clock             = clock;
        this.txManager         = txManager;
        this.targetMap         = targets.stream()
                .collect(Collectors.toMap(RetentionTarget::getDataCategory, Function.identity()));
    }

    @Scheduled(cron = "${app.privacy.purge.cron:0 0 2 * * *}")
    public void sweep() {
        schedulingLock.runIfLeader(LOCK_NAME, holderName(), properties.getLockLeaseSecs(), this::doSweep);
    }

    void doSweep() {
        Instant runStart    = clock.instant();
        Instant budgetDeadline = runStart.plus(properties.getRunBudget());

        List<RetentionPolicyEntity> policies = policyRepository.findAllRatifiedAndEnabled();
        log.info("retention_purge_sweep_start policies={} executionEnabled={}",
                policies.size(), properties.isExecutionEnabled());

        for (RetentionPolicyEntity policy : policies) {
            if (clock.instant().isAfter(budgetDeadline)) {
                log.warn("retention_purge_sweep_budget_exceeded category={}", policy.getDataCategory());
                break;
            }
            processCategorySwept(policy, runStart, budgetDeadline);
        }

        log.info("retention_purge_sweep_done durationMs={}",
                clock.instant().toEpochMilli() - runStart.toEpochMilli());
    }

    private void processCategorySwept(RetentionPolicyEntity policy,
                                      Instant runStart,
                                      Instant budgetDeadline) {
        String category = policy.getDataCategory();
        Instant cutoff  = cutoffCalculator.computeCutoff(policy.getPeriodValue(), policy.getPeriodUnit());

        PurgeRunEntity run = new PurgeRunEntity(category, runStart, cutoff);
        long examined = 0L, disposed = 0L, skipped = 0L;
        String skipReasonsJson = null;
        String outcome = "COMPLETED";
        String errorCode = null;

        try {
            if (policy.isLegalHold()) {
                skipped++;
                skipReasonsJson = "[\"LEGAL_HOLD\"]";
                outcome = "SKIPPED_LEGAL_HOLD";
                log.info("retention_purge_skipped category={} reason=LEGAL_HOLD", category);
            } else if (!properties.isExecutionEnabled()) {
                outcome = "SKIPPED_EXECUTION_DISABLED";
                log.info("retention_purge_skipped category={} reason=EXECUTION_DISABLED", category);
            } else {
                RetentionTarget target = targetMap.get(category);
                if (target == null) {
                    outcome = "SKIPPED_NO_TARGET";
                    log.warn("retention_purge_no_target category={}", category);
                } else {
                    long count = target.countEligible(cutoff);
                    examined = count;

                    List<UUID> page;
                    while (!(page = target.pageEligibleIds(cutoff, properties.getBatchSize())).isEmpty()) {
                        if (clock.instant().isAfter(budgetDeadline)) {
                            outcome = "BUDGET_EXCEEDED";
                            log.warn("retention_purge_budget_exceeded_mid_category category={} disposed={}",
                                    category, disposed);
                            break;
                        }
                        List<UUID> batch = page;
                        int batchSize = batch.size();
                        TransactionTemplate tx = new TransactionTemplate(txManager);
                        try {
                            tx.executeWithoutResult(s -> target.disposeBatch(batch));
                            disposed += batchSize;
                        } catch (Exception e) {
                            log.error("retention_purge_batch_error category={} batchSize={} error={}",
                                    category, batchSize, e.getMessage());
                            errorCode = e.getClass().getSimpleName();
                            outcome = "PARTIAL_FAILURE";
                            // continue to next batch — do not abort the entire sweep
                        }
                    }
                    if (!"BUDGET_EXCEEDED".equals(outcome) && !"PARTIAL_FAILURE".equals(outcome)) {
                        outcome = "COMPLETED";
                    }
                }
            }
        } catch (Exception e) {
            log.error("retention_purge_category_error category={} error={}", category, e.getMessage());
            outcome    = "ERROR";
            errorCode  = e.getClass().getSimpleName();
        }

        run.finish(clock.instant(), examined, disposed, skipped, skipReasonsJson, outcome, errorCode);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> purgeRunRepository.save(run));

        log.info("retention_purge_category_done category={} examined={} disposed={} skipped={} outcome={}",
                category, examined, disposed, skipped, outcome);
    }

    private static String holderName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
