package com.fieldservice.workforce.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.workforce.api.CertificationCurrencyPort;
import com.fieldservice.workforce.api.CertificationGuardPort;
import com.fieldservice.workforce.api.CertificationNotCurrentException;
import com.fieldservice.workforce.api.CertificationRef;
import com.fieldservice.workforce.application.CertificationEvaluator;
import com.fieldservice.workforce.application.CertificationEvaluator.CertSnapshot;
import com.fieldservice.workforce.web.dto.CertificationLineRequest;
import com.fieldservice.workforce.web.dto.CertificationTypeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CertificationCurrencyService implements CertificationCurrencyPort, CertificationGuardPort {

    private static final Logger log = LoggerFactory.getLogger(CertificationCurrencyService.class);

    private final CertificationTypeRepository typeRepo;
    private final TechnicianCertificationRepository certRepo;
    private final DomainEventPublisher eventPublisher;
    private final CertificationEvaluator evaluator;
    private final Clock clock;

    public CertificationCurrencyService(CertificationTypeRepository typeRepo,
                                        TechnicianCertificationRepository certRepo,
                                        DomainEventPublisher eventPublisher,
                                        Clock clock) {
        this.typeRepo       = typeRepo;
        this.certRepo       = certRepo;
        this.eventPublisher = eventPublisher;
        this.evaluator      = new CertificationEvaluator();
        this.clock          = clock;
    }

    // ── CertificationCurrencyPort ────────────────────────────────────────────

    @Override
    public boolean isCurrent(UUID technicianId, String certificationTypeCode, LocalDate atDate) {
        List<TechnicianCertificationEntity> rows =
                certRepo.findCurrentCertifications(technicianId, atDate);
        List<CertSnapshot> snaps = toSnapshots(rows);
        return evaluator.isCurrent(snaps, certificationTypeCode, atDate);
    }

    @Override
    public List<CertificationRef> currentCertifications(UUID technicianId, LocalDate atDate) {
        List<TechnicianCertificationEntity> rows =
                certRepo.findCurrentCertifications(technicianId, atDate);
        return rows.stream()
                .map(tc -> toRef(tc, atDate))
                .collect(Collectors.toList());
    }

    @Override
    public Set<UUID> technicianIdsWithCurrentCertifications(Set<String> requiredTypeCodes,
                                                             LocalDate atDate) {
        if (requiredTypeCodes == null || requiredTypeCodes.isEmpty()) {
            return Set.of();
        }
        // Use a wide candidate set: all technicians that hold at least one cert for any required code.
        // The native query's HAVING COUNT DISTINCT filters to exactly those with all required codes.
        Set<UUID> candidateIds = certRepo.findTechnicianIdsWithAllCurrentCertifications(
                requiredTypeCodes, atDate, (long) requiredTypeCodes.size());
        return new HashSet<>(candidateIds);
    }

    // ── CertificationGuardPort ───────────────────────────────────────────────

    @Override
    public List<String> assertAssignable(UUID technicianId, Set<String> requiredTypeCodes,
                                          LocalDate atDate) {
        if (requiredTypeCodes == null || requiredTypeCodes.isEmpty()) {
            return List.of();
        }
        List<TechnicianCertificationEntity> rows =
                certRepo.findCurrentCertifications(technicianId, atDate);
        List<CertSnapshot> snaps = toSnapshots(rows);

        Set<String> missing = evaluator.findMissingCodes(snaps, requiredTypeCodes, atDate);
        if (missing.isEmpty()) {
            return List.of();
        }

        // Separate regulated from non-regulated among missing codes
        Map<String, Boolean> regulatedByCode = requiredTypeCodes.stream()
                .filter(missing::contains)
                .collect(Collectors.toMap(
                        code -> code,
                        code -> typeRepo.findByCode(code)
                                .map(CertificationTypeEntity::isRegulated)
                                .orElse(true) // unknown type → treat as regulated (fail closed)
                ));

        Set<String> regulatedMissing = regulatedByCode.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        Set<String> advisoryMissing = regulatedByCode.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        if (!regulatedMissing.isEmpty()) {
            log.warn("cert.guard_refused: technician={} missingRegulated={}", technicianId, regulatedMissing);
            throw new CertificationNotCurrentException(technicianId, regulatedMissing);
        }

        return advisoryMissing.stream()
                .map(code -> "Non-regulated certification not current: " + code)
                .collect(Collectors.toList());
    }

    // ── Certification type admin ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','DISPATCHER')")
    public Page<CertificationTypeEntity> listCertificationTypes(int page, int size) {
        return typeRepo.findAllByOrderByCodeAsc(PageRequest.of(page, size));
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADMIN')")
    public CertificationTypeEntity createCertificationType(CertificationTypeRequest req) {
        if (typeRepo.existsByCode(req.code())) {
            throw new ConflictException("CertificationType with code '" + req.code() + "' already exists");
        }
        CertificationTypeEntity entity = new CertificationTypeEntity(
                req.code(), req.displayName(), req.regulated(), req.defaultValidityMonths());
        return typeRepo.save(entity);
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADMIN')")
    public CertificationTypeEntity updateCertificationType(UUID id, CertificationTypeRequest req) {
        CertificationTypeEntity entity = typeRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("CertificationType", id));
        entity.setCode(req.code());
        entity.setDisplayName(req.displayName());
        entity.setRegulated(req.regulated());
        entity.setDefaultValidityMonths(req.defaultValidityMonths());
        entity.setActive(req.active());
        return typeRepo.save(entity);
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADMIN')")
    public void deactivateCertificationType(UUID id) {
        CertificationTypeEntity entity = typeRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("CertificationType", id));
        entity.setActive(false);
        typeRepo.save(entity);
    }

    // ── Certification write (batch upsert) ───────────────────────────────────

    @Transactional
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public List<TechnicianCertificationEntity> upsertCertifications(
            UUID technicianId, List<CertificationLineRequest> items, LocalDate today) {

        List<TechnicianCertificationEntity> results = new ArrayList<>();
        for (CertificationLineRequest line : items) {
            CertificationTypeEntity typeEntity = typeRepo.findByCode(line.typeCode())
                    .orElseThrow(() -> new BusinessGuardException(
                            "Unknown certification type code: " + line.typeCode()));

            if (!typeEntity.isActive()) {
                throw new BusinessGuardException(
                        "Certification type '" + line.typeCode() + "' is deactivated and cannot be used for new records.");
            }

            if (line.expiresOn() != null && line.issuedOn().isAfter(line.expiresOn())) {
                throw new BusinessGuardException(
                        "certification-upsert",
                        "issued_on (" + line.issuedOn() + ") is after expires_on (" + line.expiresOn() + ") for type " + line.typeCode());
            }

            Optional<TechnicianCertificationEntity> existing =
                    certRepo.findActiveByTechnicianAndType(technicianId, typeEntity.getId());

            TechnicianCertificationEntity entity;
            String operation;
            if (existing.isPresent()) {
                entity = existing.get();
                entity.setCertificationType(typeEntity);
                entity.setCertificateReference(line.certificateReference());
                entity.setIssuedOn(line.issuedOn());
                entity.setExpiresOn(line.expiresOn());
                entity.setIssuingBody(line.issuingBody());
                operation = "UPDATE";
            } else {
                entity = new TechnicianCertificationEntity(
                        technicianId, typeEntity,
                        line.certificateReference(),
                        line.issuedOn(), line.expiresOn(), line.issuingBody());
                operation = "CREATE";
            }
            TechnicianCertificationEntity saved = certRepo.save(entity);
            publishEvent(saved, operation);
            results.add(saved);
        }
        return results;
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private List<CertSnapshot> toSnapshots(List<TechnicianCertificationEntity> rows) {
        return rows.stream()
                .map(tc -> new CertSnapshot(
                        tc.getCertificationType().getCode(),
                        tc.getCertificationType().isRegulated(),
                        tc.isActive(),
                        tc.getIssuedOn(),
                        tc.getExpiresOn()))
                .collect(Collectors.toList());
    }

    CertificationRef toRef(TechnicianCertificationEntity tc, LocalDate atDate) {
        CertificationTypeEntity type = tc.getCertificationType();
        boolean current = tc.getExpiresOn() == null || !tc.getExpiresOn().isBefore(atDate);
        Long daysUntilExpiry = evaluator.daysUntilExpiry(tc.getExpiresOn(), atDate);
        return new CertificationRef(
                tc.getId(),
                type.getCode(),
                type.getDisplayName(),
                type.isRegulated(),
                tc.getCertificateReference(),
                tc.getIssuedOn(),
                tc.getExpiresOn(),
                current,
                daysUntilExpiry);
    }

    private void publishEvent(TechnicianCertificationEntity saved, String operation) {
        var payload = PiiRedactionUtility.toPayloadMap(
                new com.fieldservice.outbox.payload.TechnicianCertificationChangedPayload(
                        saved.getId(),
                        saved.getTechnicianId(),
                        saved.getCertificationType().getCode(),
                        saved.getCertificationType().isRegulated(),
                        saved.getIssuedOn(),
                        saved.getExpiresOn(),
                        saved.isActive(),
                        operation,
                        clock.instant()));
        eventPublisher.publish(DomainEvent.of(
                com.fieldservice.outbox.payload.TechnicianCertificationChangedPayload.EVENT_TYPE,
                com.fieldservice.outbox.payload.TechnicianCertificationChangedPayload.AGGREGATE_TYPE,
                saved.getId(),
                clock.instant(),
                null, null,
                payload));
    }
}
