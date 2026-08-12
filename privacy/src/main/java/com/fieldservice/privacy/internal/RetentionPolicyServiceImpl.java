package com.fieldservice.privacy.internal;

import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.privacy.api.DisposalMethod;
import com.fieldservice.privacy.api.DryRunReport;
import com.fieldservice.privacy.api.RetentionPeriodUnit;
import com.fieldservice.privacy.api.RetentionPolicyService;
import com.fieldservice.privacy.api.RetentionPolicyView;
import com.fieldservice.privacy.api.RetentionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class RetentionPolicyServiceImpl implements RetentionPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyServiceImpl.class);

    /** Audit-category data_category key used for the floor guard. */
    static final String AUDIT_CATEGORY = "AUDIT_RECORDS";

    private final RetentionPolicyRepository   policyRepository;
    private final RetentionCutoffCalculator   cutoffCalculator;
    private final Map<String, RetentionTarget> targetMap;
    private final long                         auditFloorDays;

    RetentionPolicyServiceImpl(RetentionPolicyRepository policyRepository,
                                RetentionCutoffCalculator cutoffCalculator,
                                List<RetentionTarget> targets,
                                RetentionProperties properties) {
        this.policyRepository = policyRepository;
        this.cutoffCalculator = cutoffCalculator;
        this.auditFloorDays   = properties.getAuditFloorDays();
        this.targetMap        = targets.stream()
                .collect(Collectors.toMap(RetentionTarget::getDataCategory, Function.identity()));
    }

    @Override
    public Page<RetentionPolicyView> listPolicies(Pageable pageable) {
        return policyRepository.findAll(pageable).map(RetentionPolicyEntity::toView);
    }

    @Override
    public RetentionPolicyView getPolicy(UUID id) {
        return policyRepository.findById(id)
                .map(RetentionPolicyEntity::toView)
                .orElseThrow(() -> new NotFoundException("RetentionPolicy", id.toString()));
    }

    @Override
    @Transactional
    public RetentionPolicyView updatePolicy(UUID id,
                                             int periodValue,
                                             RetentionPeriodUnit periodUnit,
                                             DisposalMethod disposalMethod,
                                             boolean legalHold,
                                             boolean ratified,
                                             boolean enabled,
                                             String notes,
                                             int expectedVersion,
                                             String updatedBy) {
        RetentionPolicyEntity entity = policyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("RetentionPolicy", id.toString()));

        if (entity.getVersion() != expectedVersion) {
            throw new ConflictException("Stale version for RetentionPolicy " + id
                    + ": expected " + expectedVersion + " but current is " + entity.getVersion());
        }

        // Audit floor guard: AUDIT_RECORDS category may never have a period < 1 year
        if (AUDIT_CATEGORY.equals(entity.getDataCategory())) {
            long daysEquivalent = toDaysEquivalent(periodValue, periodUnit);
            if (daysEquivalent < auditFloorDays) {
                throw new RetentionPolicyService.AuditFloorViolationException(
                        "Audit retention policy may not be set below " + auditFloorDays
                        + " days (one-year floor). Requested period of " + periodValue
                        + " " + periodUnit + " ≈ " + daysEquivalent + " days is below the floor.");
            }
        }

        entity.setPeriodValue(periodValue);
        entity.setPeriodUnit(periodUnit);
        entity.setDisposalMethod(disposalMethod);
        entity.setLegalHold(legalHold);
        entity.setRatified(ratified);
        entity.setEnabled(enabled);
        entity.setNotes(notes);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(updatedBy);

        RetentionPolicyEntity saved = policyRepository.save(entity);
        log.info("retention_policy_updated id={} category={} period={}{} ratified={} enabled={} actor={}",
                id, entity.getDataCategory(), periodValue, periodUnit, ratified, enabled, updatedBy);
        return saved.toView();
    }

    @Override
    public DryRunReport dryRun(UUID policyId) {
        RetentionPolicyEntity policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new NotFoundException("RetentionPolicy", policyId.toString()));

        List<String> skippedReasons = new ArrayList<>();

        if (policy.isLegalHold()) {
            skippedReasons.add("LEGAL_HOLD");
        }
        if (!policy.isRatified()) {
            skippedReasons.add("NOT_RATIFIED");
        }
        if (!policy.isEnabled()) {
            skippedReasons.add("NOT_ENABLED");
        }

        Instant cutoff = cutoffCalculator.computeCutoff(policy.getPeriodValue(), policy.getPeriodUnit());

        RetentionTarget target = targetMap.get(policy.getDataCategory());
        long eligibleCount   = 0L;
        Instant oldestEligible = null;

        if (target != null && skippedReasons.isEmpty()) {
            eligibleCount  = target.countEligible(cutoff);
            oldestEligible = target.oldestEligibleAt(cutoff);
        } else if (target == null) {
            skippedReasons.add("NO_RETENTION_TARGET_REGISTERED");
            log.warn("dry_run_no_target category={} policyId={}", policy.getDataCategory(), policyId);
        }

        return new DryRunReport(
                policy.getDataCategory(),
                cutoff,
                eligibleCount,
                oldestEligible,
                policy.getDisposalMethod(),
                List.copyOf(skippedReasons));
    }

    /**
     * Conservative days-equivalent for the floor guard (uses 30 days/month, 365 days/year).
     * Exact calendar arithmetic is not needed here — the guard is intentionally conservative.
     */
    private static long toDaysEquivalent(int periodValue, RetentionPeriodUnit unit) {
        return switch (unit) {
            case DAYS   -> periodValue;
            case MONTHS -> (long) periodValue * 30;
            case YEARS  -> (long) periodValue * 365;
        };
    }
}
