package com.fieldservice.privacy.internal;

import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.privacy.api.DryRunReport;
import com.fieldservice.privacy.api.RetentionPolicyAdminPort;
import com.fieldservice.privacy.api.RetentionPolicyView;
import com.fieldservice.privacy.api.RetentionTarget;
import com.fieldservice.privacy.api.UpdateRetentionPolicyRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implements the retention policy admin port.
 *
 * <p>All public methods require {@code PRIVACY_ADMIN} or {@code ADMIN} role via
 * {@code @PreAuthorize}. The cut-off computation and audit-floor guard are
 * package-private static helpers so {@link PurgeSweepJob} can reuse them without
 * going through the proxy.
 */
@Service
@Transactional(readOnly = true)
class RetentionPolicyServiceImpl implements RetentionPolicyAdminPort {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyServiceImpl.class);

    static final int MAX_PAGE_SIZE = 50;
    static final String AUDIT_CATEGORY = "AUDIT_RECORDS";
    private static final Set<String> VALID_PERIOD_UNITS = Set.of("DAYS", "MONTHS", "YEARS");
    private static final Set<String> VALID_DISPOSAL_METHODS = Set.of("PHYSICAL_DELETE", "CRYPTO_ERASE");

    private final RetentionPolicyRepository repository;
    private final Map<String, RetentionTarget> targetsByCategory;
    private final Clock clock;
    private final RetentionPolicyProperties properties;

    RetentionPolicyServiceImpl(RetentionPolicyRepository repository,
                               List<RetentionTarget> retentionTargets,
                               Clock clock,
                               RetentionPolicyProperties properties) {
        this.repository = repository;
        this.targetsByCategory = retentionTargets.stream()
                .collect(Collectors.toMap(RetentionTarget::getDataCategory, Function.identity()));
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public PagedResponse<RetentionPolicyView> listPolicies(PageQuery pageQuery,
                                                            HttpServletRequest request) {
        int size = Math.min(pageQuery.size() <= 0 ? 20 : pageQuery.size(), MAX_PAGE_SIZE);
        int page = Math.max(pageQuery.page(), 0);

        Page<RetentionPolicy> resultPage = repository.findAllByOrderByDataCategoryAscIdAsc(
                PageRequest.of(page, size));

        List<RetentionPolicyView> records = resultPage.getContent()
                .stream().map(RetentionPolicy::toView).toList();

        PageMeta meta = PageMeta.of(resultPage.getNumber(), resultPage.getSize(),
                resultPage.getTotalElements());

        String base = UriComponentsBuilder.fromRequestUri(request).toUriString();
        String next = resultPage.hasNext()
                ? base + "?page=" + (page + 1) + "&size=" + size : null;
        String prev = page > 0
                ? base + "?page=" + (page - 1) + "&size=" + size : null;
        PageLinks links = PageLinks.of(next, prev);

        return PagedResponse.of(records, meta, links);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public RetentionPolicyView updatePolicy(UUID id, UpdateRetentionPolicyRequest request) {
        validatePeriodUnit(request.periodUnit());
        validateDisposalMethod(request.disposalMethod());

        RetentionPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("RetentionPolicy", id));

        if (!policy.getVersion().equals(request.version())) {
            throw new ConflictException("RetentionPolicy", id,
                    "Stale version: expected " + policy.getVersion() + " but got " + request.version());
        }

        if (violatesAuditFloor(policy.getDataCategory(), request.periodValue(), request.periodUnit())) {
            throw new BusinessGuardException("AUDIT_RETENTION_FLOOR",
                    "Retention period for " + policy.getDataCategory()
                    + " must be at least 1 year (audit retention floor). "
                    + "Proposed: " + request.periodValue() + " " + request.periodUnit());
        }

        policy.applyUpdate(request.periodValue(), request.periodUnit(), request.disposalMethod(),
                request.legalHold(), request.ratified(), request.enabled(), request.notes());

        RetentionPolicyView saved = repository.save(policy).toView();
        log.info("retention-policy.updated: id={} dataCategory={} periodValue={} periodUnit={} actor=context",
                saved.id(), saved.dataCategory(), saved.periodValue(), saved.periodUnit());
        return saved;
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public DryRunReport dryRun(UUID id) {
        RetentionPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("RetentionPolicy", id));

        List<String> skippedReasons = new ArrayList<>();
        Instant now = Instant.now(clock);
        Instant cutoff = computeCutoff(policy.getPeriodValue(), policy.getPeriodUnit(),
                properties.getZoneId(), now);

        if (policy.isLegalHold()) {
            skippedReasons.add("LEGAL_HOLD");
        }
        if (!policy.isRatified()) {
            skippedReasons.add("NOT_RATIFIED");
        }
        if (!policy.isEnabled()) {
            skippedReasons.add("NOT_ENABLED");
        }

        RetentionTarget target = targetsByCategory.get(policy.getDataCategory());
        if (target == null) {
            skippedReasons.add("NO_TARGET_REGISTERED");
            return new DryRunReport(policy.getDataCategory(), cutoff, -1L, null,
                    policy.getDisposalMethod(), skippedReasons);
        }

        long eligibleCount = skippedReasons.isEmpty() ? target.countEligible(cutoff) : 0L;
        Optional<Instant> oldest = skippedReasons.isEmpty() ? target.oldestEligibleAt(cutoff) : Optional.empty();

        log.info("retention-policy.dry-run: id={} dataCategory={} cutoff={} eligibleCount={}",
                id, policy.getDataCategory(), cutoff, eligibleCount);

        return new DryRunReport(policy.getDataCategory(), cutoff, eligibleCount,
                oldest.orElse(null), policy.getDisposalMethod(), skippedReasons);
    }

    // ── Package-private helpers used by PurgeSweepJob ─────────────────────────

    static Instant computeCutoff(int periodValue, String periodUnit, String zoneId, Instant referenceTime) {
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime zdt = referenceTime.atZone(zone);
        ZonedDateTime cutoffZdt = switch (periodUnit) {
            case "DAYS" -> zdt.minusDays(periodValue);
            case "MONTHS" -> zdt.minusMonths(periodValue);
            case "YEARS" -> zdt.minusYears(periodValue);
            default -> throw new IllegalArgumentException("Unknown period unit: " + periodUnit);
        };
        return cutoffZdt.toInstant();
    }

    boolean violatesAuditFloor(String dataCategory, int periodValue, String periodUnit) {
        if (!AUDIT_CATEGORY.equals(dataCategory)) {
            return false;
        }
        Instant now = Instant.now(clock);
        ZoneId zone = ZoneId.of(properties.getZoneId());
        ZonedDateTime nowZdt = now.atZone(zone);
        ZonedDateTime proposedCutoff = switch (periodUnit) {
            case "DAYS" -> nowZdt.minusDays(periodValue);
            case "MONTHS" -> nowZdt.minusMonths(periodValue);
            case "YEARS" -> nowZdt.minusYears(periodValue);
            default -> nowZdt.minusDays(periodValue);
        };
        ZonedDateTime auditFloorCutoff = nowZdt.minusYears(1);
        // The proposed cutoff must not be MORE RECENT than 1 year ago
        // (more recent = shorter retention = violates floor)
        return proposedCutoff.isAfter(auditFloorCutoff);
    }

    // ── Validation helpers ─────────────────────────────────────────────────────

    private static void validatePeriodUnit(String periodUnit) {
        if (!VALID_PERIOD_UNITS.contains(periodUnit)) {
            throw new BusinessGuardException("INVALID_PERIOD_UNIT",
                    "Invalid period unit '" + periodUnit + "'. Allowed: DAYS, MONTHS, YEARS");
        }
    }

    private static void validateDisposalMethod(String disposalMethod) {
        if (!VALID_DISPOSAL_METHODS.contains(disposalMethod)) {
            throw new BusinessGuardException("INVALID_DISPOSAL_METHOD",
                    "Invalid disposal method '" + disposalMethod
                    + "'. Allowed: PHYSICAL_DELETE, CRYPTO_ERASE");
        }
    }
}
