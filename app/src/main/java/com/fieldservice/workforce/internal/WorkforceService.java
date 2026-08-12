package com.fieldservice.workforce.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.repository.TechnicianRepository;
import com.fieldservice.workforce.api.AvailabilityPort;
import com.fieldservice.workforce.api.TechnicianDirectoryPort;
import com.fieldservice.workforce.api.TechnicianSummary;
import com.fieldservice.workforce.web.AbsenceRequest;
import com.fieldservice.workforce.web.AvailabilityWindowRequest;
import com.fieldservice.workforce.web.PositionRequest;
import com.fieldservice.workforce.web.SkillItemRequest;
import com.fieldservice.workforce.web.SkillRequest;
import com.fieldservice.workforce.web.SkillResponse;
import com.fieldservice.workforce.web.SkillRowResult;
import com.fieldservice.workforce.web.TechnicianRequest;
import com.fieldservice.workforce.web.TechnicianResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class WorkforceService implements TechnicianDirectoryPort, AvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(WorkforceService.class);
    private static final int MAX_BATCH_SIZE = 200;

    private final TechnicianRepository                   technicianRepository;
    private final SkillRepository                        skillRepository;
    private final TechnicianSkillRepository              technicianSkillRepository;
    private final TechnicianAvailabilityWindowRepository availabilityWindowRepository;
    private final TechnicianAbsenceRepository            absenceRepository;
    private final TechnicianPositionRepository           positionRepository;
    private final DomainEventPublisher                   eventPublisher;
    private final RequestScopedAccessScope               accessScope;

    private final int positionRetentionDays;

    public WorkforceService(
            TechnicianRepository technicianRepository,
            SkillRepository skillRepository,
            TechnicianSkillRepository technicianSkillRepository,
            TechnicianAvailabilityWindowRepository availabilityWindowRepository,
            TechnicianAbsenceRepository absenceRepository,
            TechnicianPositionRepository positionRepository,
            DomainEventPublisher eventPublisher,
            RequestScopedAccessScope accessScope,
            @Value("${app.workforce.position.retention-days:90}") int positionRetentionDays) {
        this.technicianRepository         = technicianRepository;
        this.skillRepository              = skillRepository;
        this.technicianSkillRepository    = technicianSkillRepository;
        this.availabilityWindowRepository = availabilityWindowRepository;
        this.absenceRepository            = absenceRepository;
        this.positionRepository           = positionRepository;
        this.eventPublisher               = eventPublisher;
        this.accessScope                  = accessScope;
        this.positionRetentionDays        = positionRetentionDays;
    }

    // ---- TechnicianDirectoryPort (read-only) --------------------------------

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public List<TechnicianSummary> findAllActive() {
        return technicianRepository.findByActiveTrue().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER','TECHNICIAN')")
    public Optional<TechnicianSummary> findById(UUID technicianId) {
        return technicianRepository.findById(technicianId).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public Optional<TechnicianSummary> findByUserId(UUID userId) {
        return technicianRepository.findByUserId(userId).map(this::toSummary);
    }

    // ---- AvailabilityPort (read-only) ----------------------------------------

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public boolean isAvailableBetween(UUID technicianId, Instant from, Instant to) {
        Technician tech = technicianRepository.findById(technicianId)
                .orElseThrow(() -> new NotFoundException("Technician not found: " + technicianId));

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tech.getTimezone());
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' for technician {}, defaulting to UTC", tech.getTimezone(), technicianId);
            zoneId = ZoneOffset.UTC;
        }

        List<AvailabilityEvaluator.AvailabilityWindow> windows =
                availabilityWindowRepository.findByTechnicianId(technicianId).stream()
                        .map(w -> new AvailabilityEvaluator.AvailabilityWindow(
                                w.getDayOfWeek(), w.getStartTime(), w.getEndTime(),
                                w.getEffectiveFrom(), w.getEffectiveTo()))
                        .collect(Collectors.toList());

        List<AvailabilityEvaluator.Absence> absences =
                absenceRepository.findByTechnicianId(technicianId).stream()
                        .map(a -> new AvailabilityEvaluator.Absence(a.getStartsAt(), a.getEndsAt()))
                        .collect(Collectors.toList());

        return AvailabilityEvaluator.isAvailableBetween(windows, absences, zoneId, from, to);
    }

    // ---- Write operations ---------------------------------------------------

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public TechnicianResponse createTechnician(TechnicianRequest request) {
        if (technicianRepository.existsByUserId(request.userId())) {
            throw new ConflictException("A technician profile already exists for userId " + request.userId());
        }
        Technician tech = new Technician(request.userId(), request.employeeCode(),
                request.displayName(), request.timezone());
        tech.setMobilePhone(request.mobilePhone());
        if (request.homeBaseSiteId() != null) tech.setHomeBaseSiteId(request.homeBaseSiteId());
        technicianRepository.save(tech);
        publishEvent("TechnicianProfileChanged", "TECHNICIAN", tech.getId());
        return toResponse(tech);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public TechnicianResponse updateTechnicianProfile(UUID technicianId, TechnicianRequest request) {
        Technician tech = loadTechnicianOrThrow(technicianId);
        tech.setDisplayName(request.displayName());
        if (request.mobilePhone() != null) tech.setMobilePhone(request.mobilePhone());
        if (request.timezone() != null)    tech.setTimezone(request.timezone());
        if (request.homeBaseSiteId() != null) tech.setHomeBaseSiteId(request.homeBaseSiteId());
        publishEvent("TechnicianProfileChanged", "TECHNICIAN", technicianId);
        return toResponse(tech);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public TechnicianResponse deactivateTechnician(UUID technicianId) {
        Technician tech = loadTechnicianOrThrow(technicianId);
        tech.setActive(false);
        publishEvent("TechnicianProfileChanged", "TECHNICIAN", technicianId);
        return toResponse(tech);
    }

    /**
     * Batch-upserts skills for a technician. Validates the entire batch first —
     * if any row fails validation, nothing is persisted.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<SkillRowResult> upsertTechnicianSkills(UUID technicianId,
                                                        List<SkillItemRequest> items) {
        if (items.size() > MAX_BATCH_SIZE) {
            throw new BusinessGuardException("Batch size " + items.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }
        loadTechnicianOrThrow(technicianId);

        // Phase 1: validate all
        List<SkillRowResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> seenCodes = new java.util.HashMap<>();

        for (int i = 0; i < items.size(); i++) {
            SkillItemRequest item = items.get(i);
            if (seenCodes.containsKey(item.skillCode())) {
                errors.add("Row " + i + ": duplicate skillCode '" + item.skillCode()
                        + "' (first seen at row " + seenCodes.get(item.skillCode()) + ")");
            }
            seenCodes.put(item.skillCode(), i);

            SkillEntity skill = skillRepository.findByCode(item.skillCode()).orElse(null);
            if (skill == null) {
                errors.add("Row " + i + ": unknown skill code '" + item.skillCode() + "'");
            } else if (!skill.isActive()) {
                errors.add("Row " + i + ": skill '" + item.skillCode() + "' is inactive");
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessGuardException("Batch validation failed: " + String.join("; ", errors));
        }

        // Phase 2: persist
        for (SkillItemRequest item : items) {
            SkillEntity skill = skillRepository.findByCode(item.skillCode()).orElseThrow();
            Optional<TechnicianSkillEntity> existing =
                    technicianSkillRepository.findByTechnicianIdAndSkillId(technicianId, skill.getId());
            if (existing.isPresent()) {
                TechnicianSkillEntity ts = existing.get();
                ts.setProficiency(item.proficiency());
                ts.setYearsExperience(item.yearsExperience());
                results.add(new SkillRowResult(item.skillCode(), "UPDATED", null));
            } else {
                TechnicianSkillEntity ts = new TechnicianSkillEntity(
                        technicianId, skill.getId(), item.proficiency(), item.yearsExperience());
                technicianSkillRepository.save(ts);
                results.add(new SkillRowResult(item.skillCode(), "CREATED", null));
            }
        }
        publishEvent("TechnicianSkillChanged", "TECHNICIAN", technicianId);
        return results;
    }

    /**
     * Replaces all availability windows for a technician.
     * Validates the entire batch before any persistence (all-or-nothing).
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void replaceAvailabilityWindows(UUID technicianId,
                                           List<AvailabilityWindowRequest> windows) {
        if (windows.size() > MAX_BATCH_SIZE) {
            throw new BusinessGuardException("Batch size exceeds maximum " + MAX_BATCH_SIZE);
        }
        loadTechnicianOrThrow(technicianId);

        // Validate: no overlapping windows for same day
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            AvailabilityWindowRequest w = windows.get(i);
            if (w.endTime() == null || w.startTime() == null) {
                errors.add("Row " + i + ": startTime and endTime are required");
                continue;
            }
            if (!w.endTime().isAfter(w.startTime())) {
                errors.add("Row " + i + ": endTime must be after startTime (midnight-spanning windows rejected)");
            }
            if (w.dayOfWeek() < 1 || w.dayOfWeek() > 7) {
                errors.add("Row " + i + ": dayOfWeek must be 1 (Monday) to 7 (Sunday)");
            }
        }
        // Check overlaps within the batch for the same day
        for (int i = 0; i < windows.size(); i++) {
            for (int j = i + 1; j < windows.size(); j++) {
                AvailabilityWindowRequest a = windows.get(i);
                AvailabilityWindowRequest b = windows.get(j);
                if (a.dayOfWeek() == b.dayOfWeek()
                        && a.startTime() != null && a.endTime() != null
                        && b.startTime() != null && b.endTime() != null) {
                    if (a.startTime().isBefore(b.endTime()) && a.endTime().isAfter(b.startTime())) {
                        errors.add("Rows " + i + " and " + j
                                + ": overlapping windows for dayOfWeek " + a.dayOfWeek());
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessGuardException("Window validation failed: " + String.join("; ", errors));
        }

        // Delete all existing, replace with new batch
        List<TechnicianAvailabilityWindowEntity> existing =
                availabilityWindowRepository.findByTechnicianId(technicianId);
        availabilityWindowRepository.deleteAll(existing);

        for (AvailabilityWindowRequest w : windows) {
            availabilityWindowRepository.save(new TechnicianAvailabilityWindowEntity(
                    technicianId, w.dayOfWeek(), w.startTime(), w.endTime(),
                    w.effectiveFrom(), w.effectiveTo()));
        }
        publishEvent("TechnicianAvailabilityChanged", "TECHNICIAN", technicianId);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void addAbsence(UUID technicianId, AbsenceRequest request) {
        loadTechnicianOrThrow(technicianId);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new BusinessGuardException("endsAt must be after startsAt");
        }
        absenceRepository.save(new TechnicianAbsenceEntity(
                technicianId, request.startsAt(), request.endsAt(), request.reason()));
        publishEvent("TechnicianAvailabilityChanged", "TECHNICIAN", technicianId);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','TECHNICIAN')")
    public void updatePosition(UUID technicianId, PositionRequest request) {
        loadTechnicianOrThrow(technicianId);
        // Last-write-wins-by-capturedAt: only store if newer than existing
        Optional<TechnicianPositionEntity> existing =
                positionRepository.findLatestByTechnicianId(technicianId);
        if (existing.isPresent()
                && !request.capturedAt().isAfter(existing.get().getCapturedAt())) {
            log.debug("Position update for technician {} ignored: capturedAt {} is not after stored {}",
                    technicianId, request.capturedAt(), existing.get().getCapturedAt());
            return;
        }
        int accuracy = request.accuracyMetres() != null ? request.accuracyMetres() : 0;
        LocalDate retainUntil = LocalDate.now().plusDays(positionRetentionDays);
        positionRepository.save(new TechnicianPositionEntity(
                technicianId,
                request.latitude().toString(),
                request.longitude().toString(),
                request.capturedAt(),
                accuracy,
                retainUntil));
    }

    // ---- Skill admin -------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    public SkillResponse createSkill(SkillRequest request) {
        if (skillRepository.existsByCode(request.code())) {
            throw new ConflictException("Skill code already exists: " + request.code());
        }
        SkillEntity skill = new SkillEntity(request.code(), request.displayName());
        skillRepository.save(skill);
        return toSkillResponse(skill);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public List<SkillResponse> listSkills(boolean activeOnly) {
        List<SkillEntity> skills = activeOnly
                ? skillRepository.findByActiveTrue()
                : skillRepository.findAll();
        return skills.stream().map(this::toSkillResponse).collect(Collectors.toList());
    }

    @PreAuthorize("hasRole('ADMIN')")
    public SkillResponse deactivateSkill(String code) {
        SkillEntity skill = skillRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Skill not found: " + code));
        skill.setActive(false);
        return toSkillResponse(skill);
    }

    // ---- Position retention purge ------------------------------------------

    @Transactional
    public int purgePositionsOlderThan(Instant before) {
        int deleted = positionRepository.deleteOlderThan(before);
        if (deleted > 0) {
            log.info("Purged {} technician position record(s) older than {}", deleted, before);
        }
        return deleted;
    }

    // ---- Helpers ------------------------------------------------------------

    private Technician loadTechnicianOrThrow(UUID technicianId) {
        return technicianRepository.findById(technicianId)
                .orElseThrow(() -> new NotFoundException("Technician not found: " + technicianId));
    }

    private TechnicianSummary toSummary(Technician t) {
        return new TechnicianSummary(
                t.getId(), t.getUserId(), t.getEmployeeCode(),
                t.getDisplayName() != null ? t.getDisplayName() : t.getFullName(),
                t.getTimezone(), t.getHomeBaseSiteId(), t.isActive());
    }

    private TechnicianResponse toResponse(Technician t) {
        return new TechnicianResponse(
                t.getId(), t.getUserId(), t.getEmployeeCode(),
                t.getDisplayName() != null ? t.getDisplayName() : t.getFullName(),
                t.getTimezone(), t.getHomeBaseSiteId(), t.isActive(), t.getVersion());
    }

    private void publishEvent(String eventType, String aggregateType, UUID aggregateId) {
        var scope = accessScope.get();
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                eventType,
                aggregateType,
                aggregateId,
                Instant.now(),
                MDC.get("traceId"),
                scope != null ? scope.userId() : null,
                Map.of("aggregateId", aggregateId.toString())));
    }

    private SkillResponse toSkillResponse(SkillEntity s) {
        return new SkillResponse(s.getId(), s.getCode(), s.getDisplayName(), s.isActive());
    }
}
