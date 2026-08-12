package com.fieldservice.workforce.internal;

import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.repository.TechnicianRepository;
import com.fieldservice.workforce.api.CertificationSummary;
import com.fieldservice.workforce.web.ReadinessAggregateResponse;
import com.fieldservice.workforce.web.ReadinessRequirementRequest;
import com.fieldservice.workforce.web.ReadinessRequirementResponse;
import com.fieldservice.workforce.web.ReadinessSnapshotResponse;
import com.fieldservice.workforce.web.TechnicianGapResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core readiness report service: evaluates certification data-completeness for all
 * active technicians, serves paged gap lists, and manages idempotent weekly snapshots.
 */
@Service
@Transactional(readOnly = true)
public class ReadinessReportService {

    private static final int  GATE_TARGET       = 100;
    private static final int  MAX_PAGE_SIZE      = 50;

    private final TechnicianRepository           technicianRepository;
    private final ReadinessRequirementRepository requirementRepository;
    private final ReadinessSnapshotRepository    snapshotRepository;
    private final CertificationCurrencyService   certCurrencyService;

    public ReadinessReportService(
            TechnicianRepository technicianRepository,
            ReadinessRequirementRepository requirementRepository,
            ReadinessSnapshotRepository snapshotRepository,
            CertificationCurrencyService certCurrencyService) {
        this.technicianRepository  = technicianRepository;
        this.requirementRepository = requirementRepository;
        this.snapshotRepository    = snapshotRepository;
        this.certCurrencyService   = certCurrencyService;
    }

    // ---- Public API ---------------------------------------------------------

    public ReadinessAggregateResponse computeReadiness(LocalDate atDate, int warningWindowDays) {
        List<ReadinessRequirementEntity> requirements = activeRequirements();
        List<Technician> activeTechs = technicianRepository.findByActiveTrue();

        if (activeTechs.isEmpty()) {
            return new ReadinessAggregateResponse(
                    null, 0, 0, GATE_TARGET, false, 0,
                    definitionVersion(requirements), Instant.now(), false);
        }

        List<TechnicianCompletenessResult> results = evaluateAll(activeTechs, atDate,
                requirements, warningWindowDays);

        int completeTechs  = (int) results.stream().filter(TechnicianCompletenessResult::complete).count();
        int activeTechCount = activeTechs.size();
        BigDecimal percent  = BigDecimal.valueOf(completeTechs * 100L)
                .divide(BigDecimal.valueOf(activeTechCount), 2, RoundingMode.HALF_UP);
        boolean gateMet     = completeTechs == activeTechCount;
        int blocking        = activeTechCount - completeTechs;

        return new ReadinessAggregateResponse(
                percent, completeTechs, activeTechCount, GATE_TARGET, gateMet, blocking,
                definitionVersion(requirements), Instant.now(), true);
    }

    public PagedResponse<TechnicianGapResponse> getGaps(
            LocalDate atDate, int warningWindowDays, int page, int size) {
        List<ReadinessRequirementEntity> requirements = activeRequirements();
        List<Technician> activeTechs = technicianRepository.findByActiveTrue();

        List<TechnicianGapResponse> blocking = evaluateAll(activeTechs, atDate,
                requirements, warningWindowDays)
                .stream()
                .filter(TechnicianCompletenessResult::isBlocking)
                .map(this::toGapResponse)
                .collect(Collectors.toList());

        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int total      = blocking.size();
        int fromIdx    = Math.min(page * cappedSize, total);
        int toIdx      = Math.min(fromIdx + cappedSize, total);

        PageMeta  meta    = PageMeta.of(page, cappedSize, total);
        String    next    = toIdx < total
                ? "/api/v1/reports/certification-readiness/gaps?page=" + (page + 1) + "&size=" + cappedSize
                : null;
        String    prev    = page > 0
                ? "/api/v1/reports/certification-readiness/gaps?page=" + (page - 1) + "&size=" + cappedSize
                : null;

        return PagedResponse.of(blocking.subList(fromIdx, toIdx), meta, PageLinks.of(next, prev));
    }

    public List<TechnicianGapResponse> getAllGapsForCsv(LocalDate atDate, int warningWindowDays) {
        List<ReadinessRequirementEntity> requirements = activeRequirements();
        List<Technician> activeTechs = technicianRepository.findByActiveTrue();
        return evaluateAll(activeTechs, atDate, requirements, warningWindowDays)
                .stream()
                .filter(TechnicianCompletenessResult::isBlocking)
                .map(this::toGapResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReadinessSnapshotResponse generateSnapshot(
            LocalDate atDate, int warningWindowDays, String isoWeek) {
        List<ReadinessRequirementEntity> requirements = activeRequirements();
        List<Technician> activeTechs = technicianRepository.findByActiveTrue();

        BigDecimal percent;
        int complete;
        int activeCount = activeTechs.size();
        boolean applicable;
        boolean gateMet;

        if (activeCount == 0) {
            percent    = null;
            complete   = 0;
            applicable = false;
            gateMet    = false;
        } else {
            List<TechnicianCompletenessResult> results = evaluateAll(activeTechs, atDate,
                    requirements, warningWindowDays);
            complete   = (int) results.stream().filter(TechnicianCompletenessResult::complete).count();
            percent    = BigDecimal.valueOf(complete * 100L)
                    .divide(BigDecimal.valueOf(activeCount), 2, RoundingMode.HALF_UP);
            gateMet    = complete == activeCount;
            applicable = true;
        }

        String defVer = definitionVersion(requirements);
        ReadinessSnapshotEntity snapshot = snapshotRepository.findByIsoWeek(isoWeek)
                .orElse(null);
        if (snapshot == null) {
            snapshot = new ReadinessSnapshotEntity(isoWeek, percent, complete, activeCount,
                    gateMet, defVer);
            snapshotRepository.save(snapshot);
        } else {
            snapshot.update(percent, complete, activeCount, gateMet, defVer);
        }

        return toSnapshotResponse(snapshot);
    }

    public PagedResponse<ReadinessSnapshotResponse> listSnapshots(int page, int size) {
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ReadinessSnapshotEntity> pg = snapshotRepository.findAllByOrderByGeneratedAtDesc(
                PageRequest.of(page, cappedSize));
        PageMeta meta  = PageMeta.of(page, cappedSize, pg.getTotalElements());
        String   next  = pg.hasNext()
                ? "/api/v1/reports/certification-readiness/snapshots?page=" + (page + 1) + "&size=" + cappedSize
                : null;
        String   prev  = page > 0
                ? "/api/v1/reports/certification-readiness/snapshots?page=" + (page - 1) + "&size=" + cappedSize
                : null;
        return PagedResponse.of(
                pg.getContent().stream().map(this::toSnapshotResponse).collect(Collectors.toList()),
                meta, PageLinks.of(next, prev));
    }

    // ---- Requirement CRUD (ADMIN only) --------------------------------------

    @Transactional(readOnly = true)
    public List<ReadinessRequirementResponse> listRequirements() {
        return requirementRepository.findAll().stream()
                .map(this::toRequirementResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReadinessRequirementResponse createRequirement(ReadinessRequirementRequest req) {
        ReadinessRequirementKind kind = ReadinessRequirementKind.valueOf(req.requirementKind());
        ReadinessRequirementEntity entity = new ReadinessRequirementEntity(
                kind,
                req.fieldName(),
                req.certificationTypeCode(),
                req.technicianCategory());
        return toRequirementResponse(requirementRepository.save(entity));
    }

    @Transactional
    public ReadinessRequirementResponse updateRequirement(UUID id, ReadinessRequirementRequest req) {
        ReadinessRequirementEntity entity = requirementRepository.findById(id)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException(
                        "ReadinessRequirement", id));
        if (req.fieldName() != null)              entity.setFieldName(req.fieldName());
        if (req.certificationTypeCode() != null)  entity.setCertificationTypeCode(req.certificationTypeCode());
        if (req.technicianCategory() != null)     entity.setTechnicianCategory(req.technicianCategory());
        return toRequirementResponse(entity);
    }

    @Transactional
    public ReadinessRequirementResponse deactivateRequirement(UUID id) {
        ReadinessRequirementEntity entity = requirementRepository.findById(id)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException(
                        "ReadinessRequirement", id));
        entity.setActive(false);
        return toRequirementResponse(entity);
    }

    // ---- Private helpers ----------------------------------------------------

    private List<ReadinessRequirementEntity> activeRequirements() {
        List<ReadinessRequirementEntity> requirements = requirementRepository.findByActiveTrue();
        if (requirements.isEmpty()) {
            throw new BusinessGuardException(
                    "NO_ACTIVE_REQUIREMENTS",
                    "No active readiness requirements are configured. " +
                    "Configure at least one requirement before generating a report.");
        }
        return requirements;
    }

    private List<TechnicianCompletenessResult> evaluateAll(
            List<Technician> techs, LocalDate atDate,
            List<ReadinessRequirementEntity> requirements, int warningWindowDays) {
        return techs.stream()
                .map(t -> {
                    TechnicianReadModel model = new TechnicianReadModel(
                            t.getId(), t.getEmployeeCode(), t.getDisplayName(),
                            t.getMobilePhone(), t.getTimezone(), t.getHomeBaseSiteId());
                    List<CertificationSummary> certs =
                            certCurrencyService.allCertificationsInternal(t.getId(), atDate);
                    return CompletenessEvaluator.evaluate(model, certs, requirements, warningWindowDays);
                })
                .collect(Collectors.toList());
    }

    private String definitionVersion(List<ReadinessRequirementEntity> requirements) {
        return "v" + requirements.size();
    }

    private TechnicianGapResponse toGapResponse(TechnicianCompletenessResult r) {
        return new TechnicianGapResponse(
                r.technicianId(), r.employeeCode(), r.displayName(),
                r.missingFields(), r.missingCertificationTypes(),
                r.expiredCertificationTypes(), r.expiringSoonCertificationTypes());
    }

    private ReadinessSnapshotResponse toSnapshotResponse(ReadinessSnapshotEntity e) {
        return new ReadinessSnapshotResponse(
                e.getId(), e.getIsoWeek(), e.getReadinessPercent(),
                e.getCompleteTechnicians(), e.getActiveTechnicians(),
                e.isGateMet(), e.getDefinitionVersion(), e.getGeneratedAt());
    }

    private ReadinessRequirementResponse toRequirementResponse(ReadinessRequirementEntity e) {
        return new ReadinessRequirementResponse(
                e.getId(),
                e.getRequirementKind().name(),
                e.getFieldName(),
                e.getCertificationTypeCode(),
                e.getTechnicianCategory(),
                e.isActive());
    }
}
