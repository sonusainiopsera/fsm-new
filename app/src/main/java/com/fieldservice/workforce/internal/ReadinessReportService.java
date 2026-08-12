package com.fieldservice.workforce.internal;

import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.internal.CompletenessEvaluator.CertEntry;
import com.fieldservice.workforce.internal.CompletenessEvaluator.RequirementSpec;
import com.fieldservice.workforce.internal.CompletenessEvaluator.TechnicianCompletenessResult;
import com.fieldservice.workforce.internal.CompletenessEvaluator.TechnicianProfile;
import com.fieldservice.workforce.web.dto.ReadinessRequirementRequest;
import com.fieldservice.workforce.web.dto.ReadinessRequirementResponse;
import com.fieldservice.workforce.web.dto.ReadinessSnapshotResponse;
import com.fieldservice.workforce.web.dto.ReadinessSummaryResponse;
import com.fieldservice.workforce.web.dto.TechnicianGapRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Certification data-readiness report service (WO-122).
 *
 * <h3>Evaluation flow</h3>
 * <ol>
 *   <li>Load all active requirements — 422 if none configured (fail-open risk).</li>
 *   <li>Load all active technicians + their profile fields via a single JDBC query.</li>
 *   <li>Load all active certification records in bulk (one query, not N+1).</li>
 *   <li>Run the framework-free {@link CompletenessEvaluator} for each technician.</li>
 *   <li>Compute aggregate: completeTechnicians / activeTechnicians.</li>
 *   <li>Gate verdict: 100 percent required (gateMet = completeTechnicians == activeTechnicians).</li>
 * </ol>
 *
 * <h3>Governance note</h3>
 * This report evidences the Phase 1 exit gate:
 * "certification data-readiness audit complete with a remediation plan" (AC-9).
 * The definition version in each response and snapshot identifies the requirement
 * set in force at evaluation time, so historical comparisons remain meaningful.
 */
@Service
@Transactional(readOnly = true)
public class ReadinessReportService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessReportService.class);

    /** Default warning window for expiring-soon classification (days). */
    private static final int DEFAULT_WARNING_WINDOW_DAYS = 30;

    private final ReadinessRequirementRepository requirementRepo;
    private final ReadinessSnapshotRepository snapshotRepo;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CompletenessEvaluator evaluator;

    public ReadinessReportService(ReadinessRequirementRepository requirementRepo,
                                   ReadinessSnapshotRepository snapshotRepo,
                                   JdbcTemplate jdbc,
                                   Clock clock) {
        this.requirementRepo = requirementRepo;
        this.snapshotRepo    = snapshotRepo;
        this.jdbc            = jdbc;
        this.clock           = clock;
        this.evaluator       = new CompletenessEvaluator();
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public ReadinessSummaryResponse getSummary() {
        List<ReadinessRequirementEntity> reqs = loadActiveRequirementsOrThrow();
        List<RequirementSpec> specs           = toSpecs(reqs);
        String defVersion                     = computeDefinitionVersion(reqs);

        LocalDate businessDate = LocalDate.now(clock);
        Instant evaluatedAt    = clock.instant();

        List<TechnicianProfile> technicians = loadActiveTechnicians();
        if (technicians.isEmpty()) {
            return new ReadinessSummaryResponse(
                    null, 0, 0, 100, false, 0, defVersion, evaluatedAt, false);
        }

        Map<UUID, List<CertEntry>> certsByTech = loadAllCertsByTechnician();

        int complete = 0;
        for (TechnicianProfile profile : technicians) {
            List<CertEntry> certs = certsByTech.getOrDefault(profile.technicianId(), List.of());
            TechnicianCompletenessResult result =
                    evaluator.evaluate(profile, certs, specs, businessDate, DEFAULT_WARNING_WINDOW_DAYS);
            if (result.isComplete()) complete++;
        }

        int total          = technicians.size();
        int blocking       = total - complete;
        boolean gateMet    = complete == total;
        BigDecimal percent = percent(complete, total);

        return new ReadinessSummaryResponse(
                percent, complete, total, 100, gateMet, blocking,
                defVersion, evaluatedAt, true);
    }

    // ── Gap detail ───────────────────────────────────────────────────────────

    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public PagedResponse<TechnicianGapRecord> getGaps(int page, int size) {
        int clampedSize = Math.min(size, 50);

        List<ReadinessRequirementEntity> reqs = loadActiveRequirementsOrThrow();
        List<RequirementSpec> specs           = toSpecs(reqs);
        LocalDate businessDate                = LocalDate.now(clock);

        List<TechnicianProfile> technicians   = loadActiveTechnicians();
        Map<UUID, List<CertEntry>> certsByTech = loadAllCertsByTechnician();

        List<TechnicianGapRecord> gaps = new ArrayList<>();
        for (TechnicianProfile profile : technicians) {
            List<CertEntry> certs = certsByTech.getOrDefault(profile.technicianId(), List.of());
            TechnicianCompletenessResult result =
                    evaluator.evaluate(profile, certs, specs, businessDate, DEFAULT_WARNING_WINDOW_DAYS);
            if (!result.isComplete()) {
                gaps.add(toGapRecord(result));
            }
        }

        // Sort by displayName for stable ordering (UUID tie-break)
        gaps.sort(Comparator.comparing(
                (TechnicianGapRecord g) -> g.displayName() != null ? g.displayName() : "")
                .thenComparing(TechnicianGapRecord::technicianId));

        int total      = gaps.size();
        int fromIndex  = Math.min(page * clampedSize, total);
        int toIndex    = Math.min(fromIndex + clampedSize, total);
        List<TechnicianGapRecord> pageContent = gaps.subList(fromIndex, toIndex);

        PageMeta  meta  = PageMeta.of(page, pageContent.size(), (long) total);
        PageLinks links = PageLinks.none();
        return PagedResponse.of(pageContent, meta, links);
    }

    // ── CSV export ───────────────────────────────────────────────────────────

    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public String exportGapsCsv() {
        List<ReadinessRequirementEntity> reqs = loadActiveRequirementsOrThrow();
        List<RequirementSpec> specs           = toSpecs(reqs);
        LocalDate businessDate                = LocalDate.now(clock);

        List<TechnicianProfile> technicians   = loadActiveTechnicians();
        Map<UUID, List<CertEntry>> certsByTech = loadAllCertsByTechnician();

        StringBuilder sb = new StringBuilder();
        sb.append("technician_id,employee_code,display_name,missing_fields,missing_cert_types,expired_cert_types,expiring_soon_cert_types\n");

        technicians.stream()
                .sorted(Comparator.comparing(p -> p.displayName() != null ? p.displayName() : ""))
                .forEach(profile -> {
                    List<CertEntry> certs = certsByTech.getOrDefault(profile.technicianId(), List.of());
                    TechnicianCompletenessResult result =
                            evaluator.evaluate(profile, certs, specs, businessDate, DEFAULT_WARNING_WINDOW_DAYS);
                    if (!result.isComplete()) {
                        sb.append(csvRow(result));
                    }
                });

        return sb.toString();
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public PagedResponse<ReadinessSnapshotResponse> getSnapshots(int page, int size) {
        int clampedSize = Math.min(size, 50);
        Page<ReadinessSnapshotEntity> dbPage =
                snapshotRepo.findAllByOrderByIsoWeekDesc(PageRequest.of(page, clampedSize));

        List<ReadinessSnapshotResponse> items = dbPage.getContent().stream()
                .map(this::toSnapshotResponse)
                .collect(Collectors.toList());

        PageMeta  meta  = PageMeta.of(page, items.size(), dbPage.getTotalElements());
        PageLinks links = PageLinks.none();
        return PagedResponse.of(items, meta, links);
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADMIN')")
    public ReadinessSnapshotResponse generateSnapshot() {
        List<ReadinessRequirementEntity> reqs = loadActiveRequirementsOrThrow();
        List<RequirementSpec> specs           = toSpecs(reqs);
        String defVersion                     = computeDefinitionVersion(reqs);

        LocalDate businessDate   = LocalDate.now(clock);
        String isoWeek           = isoWeekKey(businessDate);
        Instant now              = clock.instant();

        List<TechnicianProfile> technicians  = loadActiveTechnicians();
        Map<UUID, List<CertEntry>> certsByTech = loadAllCertsByTechnician();

        int complete = 0;
        for (TechnicianProfile profile : technicians) {
            List<CertEntry> certs = certsByTech.getOrDefault(profile.technicianId(), List.of());
            if (evaluator.evaluate(profile, certs, specs, businessDate, DEFAULT_WARNING_WINDOW_DAYS).isComplete()) {
                complete++;
            }
        }

        int total       = technicians.size();
        boolean applicable = total > 0;
        boolean gateMet = applicable && complete == total;
        BigDecimal pct  = applicable ? percent(complete, total) : null;

        // Idempotent upsert — delete existing row for same iso_week, then insert
        Optional<ReadinessSnapshotEntity> existing = snapshotRepo.findByIsoWeek(isoWeek);
        existing.ifPresent(snapshotRepo::delete);
        snapshotRepo.flush();

        ReadinessSnapshotEntity snap = new ReadinessSnapshotEntity(
                isoWeek, pct, complete, total, gateMet, defVersion, now, applicable);
        ReadinessSnapshotEntity saved = snapshotRepo.save(snap);

        log.info("readiness_snapshot_generated isoWeek={} readinessPercent={} gateMet={} definitionVersion={}",
                isoWeek, pct, gateMet, defVersion);

        return toSnapshotResponse(saved);
    }

    // ── Requirement CRUD ─────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('ADMIN')")
    public List<ReadinessRequirementResponse> listRequirements() {
        return requirementRepo.findAllByActiveTrueOrderByRequirementKindAscCertificationTypeCodeAscFieldNameAsc()
                .stream()
                .map(this::toRequirementResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADMIN')")
    public ReadinessRequirementResponse createRequirement(ReadinessRequirementRequest req) {
        RequirementKind kind;
        try {
            kind = RequirementKind.valueOf(req.requirementKind());
        } catch (IllegalArgumentException e) {
            throw new BusinessGuardException("Unknown requirementKind: " + req.requirementKind());
        }
        validateRequirementRequest(kind, req);

        ReadinessRequirementEntity entity = new ReadinessRequirementEntity(
                kind, req.fieldName(), req.certificationTypeCode(), req.technicianCategory());
        entity.setActive(req.active());
        return toRequirementResponse(requirementRepo.save(entity));
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADMIN')")
    public ReadinessRequirementResponse updateRequirement(UUID id, ReadinessRequirementRequest req) {
        ReadinessRequirementEntity entity = requirementRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("ReadinessRequirement", id));

        RequirementKind kind;
        try {
            kind = RequirementKind.valueOf(req.requirementKind());
        } catch (IllegalArgumentException e) {
            throw new BusinessGuardException("Unknown requirementKind: " + req.requirementKind());
        }
        validateRequirementRequest(kind, req);

        entity.setRequirementKind(kind);
        entity.setFieldName(req.fieldName());
        entity.setCertificationTypeCode(req.certificationTypeCode());
        entity.setTechnicianCategory(req.technicianCategory());
        entity.setActive(req.active());
        return toRequirementResponse(requirementRepo.save(entity));
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADMIN')")
    public void deactivateRequirement(UUID id) {
        ReadinessRequirementEntity entity = requirementRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("ReadinessRequirement", id));
        entity.setActive(false);
        requirementRepo.save(entity);
    }

    // ── Data loading (JDBC bulk queries) ────────────────────────────────────

    private List<TechnicianProfile> loadActiveTechnicians() {
        return jdbc.queryForList("""
                SELECT t.id AS technician_id,
                       t.employee_no,
                       t.phone,
                       u.display_name,
                       u.email
                  FROM technician t
                  JOIN app_user u ON u.id = t.user_id
                 WHERE t.is_active = true
                   AND u.is_active = true
                 ORDER BY u.display_name, t.id
                """)
                .stream()
                .map(row -> new TechnicianProfile(
                        (UUID) row.get("technician_id"),
                        (String) row.get("employee_no"),
                        (String) row.get("display_name"),
                        (String) row.get("email"),
                        (String) row.get("phone"),
                        null // category not yet in schema
                ))
                .collect(Collectors.toList());
    }

    private Map<UUID, List<CertEntry>> loadAllCertsByTechnician() {
        LocalDate today = LocalDate.now(clock);
        return jdbc.queryForList("""
                SELECT tc.technician_id,
                       ct.code AS type_code,
                       tc.expires_on,
                       tc.active
                  FROM technician_certification tc
                  JOIN certification_type ct ON ct.id = tc.certification_type_id
                 WHERE tc.active = true
                   AND ct.active = true
                """)
                .stream()
                .collect(Collectors.groupingBy(
                        row -> (UUID) row.get("technician_id"),
                        Collectors.mapping(
                                row -> {
                                    java.sql.Date sqlDate = (java.sql.Date) row.get("expires_on");
                                    LocalDate expiresOn = sqlDate != null ? sqlDate.toLocalDate() : null;
                                    return new CertEntry(
                                            (String) row.get("type_code"),
                                            expiresOn,
                                            Boolean.TRUE.equals(row.get("active")));
                                },
                                Collectors.toList())));
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private List<ReadinessRequirementEntity> loadActiveRequirementsOrThrow() {
        List<ReadinessRequirementEntity> reqs =
                requirementRepo.findAllByActiveTrueOrderByRequirementKindAscCertificationTypeCodeAscFieldNameAsc();
        if (reqs.isEmpty()) {
            throw new BusinessGuardException(
                    "readiness-no-requirements",
                    "Readiness cannot be evaluated: no active requirements are configured. " +
                    "An ADMIN must add at least one active readiness_requirement before this report is meaningful.");
        }
        return reqs;
    }

    private List<RequirementSpec> toSpecs(List<ReadinessRequirementEntity> reqs) {
        return reqs.stream()
                .map(r -> new RequirementSpec(
                        r.getRequirementKind(),
                        r.getFieldName(),
                        r.getCertificationTypeCode()))
                .collect(Collectors.toList());
    }

    private String computeDefinitionVersion(List<ReadinessRequirementEntity> reqs) {
        // Stable hash of sorted requirement IDs — changes whenever the active set changes
        String joined = reqs.stream()
                .map(r -> r.getId().toString())
                .sorted()
                .collect(Collectors.joining(","));
        return Integer.toHexString(joined.hashCode());
    }

    private BigDecimal percent(int numerator, int denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(2, RoundingMode.HALF_UP);
    }

    static String isoWeekKey(LocalDate date) {
        int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int week     = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return "%d-W%02d".formatted(weekYear, week);
    }

    private void validateRequirementRequest(RequirementKind kind, ReadinessRequirementRequest req) {
        if (kind == RequirementKind.PROFILE_FIELD) {
            if (req.fieldName() == null || req.fieldName().isBlank()) {
                throw new BusinessGuardException(
                        "fieldName is required for PROFILE_FIELD requirements");
            }
            if (req.certificationTypeCode() != null) {
                throw new BusinessGuardException(
                        "certificationTypeCode must be null for PROFILE_FIELD requirements");
            }
        } else {
            if (req.certificationTypeCode() == null || req.certificationTypeCode().isBlank()) {
                throw new BusinessGuardException(
                        "certificationTypeCode is required for CERTIFICATION_TYPE requirements");
            }
            if (req.fieldName() != null) {
                throw new BusinessGuardException(
                        "fieldName must be null for CERTIFICATION_TYPE requirements");
            }
        }
    }

    private TechnicianGapRecord toGapRecord(TechnicianCompletenessResult r) {
        return new TechnicianGapRecord(
                r.technicianId(),
                r.employeeCode(),
                r.displayName(),
                r.missingFields(),
                r.missingCertificationTypes(),
                r.expiredCertificationTypes(),
                r.expiringSoonCertificationTypes());
    }

    private ReadinessSnapshotResponse toSnapshotResponse(ReadinessSnapshotEntity e) {
        return new ReadinessSnapshotResponse(
                e.getId(), e.getIsoWeek(), e.getReadinessPercent(),
                e.getCompleteTechnicians(), e.getActiveTechnicians(),
                e.isGateMet(), e.getDefinitionVersion(),
                e.getGeneratedAt(), e.isApplicable());
    }

    private ReadinessRequirementResponse toRequirementResponse(ReadinessRequirementEntity e) {
        return new ReadinessRequirementResponse(
                e.getId(),
                e.getRequirementKind().name(),
                e.getFieldName(),
                e.getCertificationTypeCode(),
                e.getTechnicianCategory(),
                e.isActive(),
                e.getVersion() != null ? e.getVersion() : 0);
    }

    private String csvRow(TechnicianCompletenessResult r) {
        return "\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n".formatted(
                r.technicianId(),
                csvSafe(r.employeeCode()),
                csvSafe(r.displayName()),
                String.join("|", r.missingFields()),
                String.join("|", r.missingCertificationTypes()),
                String.join("|", r.expiredCertificationTypes()),
                String.join("|", r.expiringSoonCertificationTypes()));
    }

    private String csvSafe(String value) {
        return value == null ? "" : value.replace("\"", "\"\"");
    }
}
