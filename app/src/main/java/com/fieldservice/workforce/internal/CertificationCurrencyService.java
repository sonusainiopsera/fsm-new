package com.fieldservice.workforce.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.CertificationNotCurrentException;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.technician.domain.TechnicianCertification;
import com.fieldservice.technician.repository.TechnicianCertificationRepository;
import com.fieldservice.technician.repository.TechnicianRepository;
import com.fieldservice.workforce.api.CertificationCurrencyPort;
import com.fieldservice.workforce.api.CertificationGuardPort;
import com.fieldservice.workforce.api.CertificationSummary;
import com.fieldservice.workforce.web.CertificationItemRequest;
import com.fieldservice.workforce.web.CertificationRowResult;
import com.fieldservice.workforce.web.CertificationTypeRequest;
import com.fieldservice.workforce.web.CertificationTypeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core certification currency service — implements both public dispatch seams.
 *
 * <h3>Currency invariant</h3>
 * Currency is ALWAYS computed as a SQL predicate ({@code expires_on IS NULL OR expires_on >= atDate}).
 * No boolean flag is ever stored, read, or cached.
 *
 * <h3>Fail-closed</h3>
 * Any evaluation error (unreadable date, unknown type code) denies eligibility and
 * logs a security-relevant event rather than defaulting to eligible.
 */
@Service
@Transactional
public class CertificationCurrencyService implements CertificationCurrencyPort, CertificationGuardPort {

    private static final Logger log = LoggerFactory.getLogger(CertificationCurrencyService.class);
    private static final int MAX_BATCH_SIZE = 200;

    private final CertificationTypeRepository        typeRepository;
    private final TechnicianCertificationRepository  certRepository;
    private final TechnicianRepository               technicianRepository;
    private final DomainEventPublisher               eventPublisher;
    private final RequestScopedAccessScope           accessScope;

    public CertificationCurrencyService(
            CertificationTypeRepository typeRepository,
            TechnicianCertificationRepository certRepository,
            TechnicianRepository technicianRepository,
            DomainEventPublisher eventPublisher,
            RequestScopedAccessScope accessScope) {
        this.typeRepository      = typeRepository;
        this.certRepository      = certRepository;
        this.technicianRepository = technicianRepository;
        this.eventPublisher      = eventPublisher;
        this.accessScope         = accessScope;
    }

    // ---- CertificationCurrencyPort ------------------------------------------

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public boolean isCurrent(UUID technicianId, String certificationTypeCode, LocalDate atDate) {
        try {
            return certRepository.existsCurrentByTechnicianIdAndTypeCode(
                    technicianId, certificationTypeCode, atDate);
        } catch (Exception e) {
            log.error("certification_currency_eval_failed technician_id={} type_code={} reason={}",
                    technicianId, certificationTypeCode, e.getMessage());
            return false; // fail-closed
        }
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER','TECHNICIAN')")
    public List<CertificationSummary> currentCertifications(UUID technicianId, LocalDate atDate) {
        List<TechnicianCertification> certs =
                certRepository.findCurrentByTechnicianId(technicianId, atDate);

        // Load type details for the certs we found
        Set<UUID> typeIds = certs.stream()
                .map(TechnicianCertification::getCertificationTypeId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<UUID, CertificationTypeEntity> typeById = typeIds.isEmpty()
                ? Collections.emptyMap()
                : typeRepository.findAllById(typeIds).stream()
                        .collect(Collectors.toMap(CertificationTypeEntity::getId, t -> t));

        return certs.stream()
                .map(c -> toSummary(c, typeById.get(c.getCertificationTypeId()), atDate))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public Set<UUID> technicianIdsWithCurrentCertifications(
            Set<String> requiredTypeCodes, LocalDate atDate) {
        if (requiredTypeCodes == null || requiredTypeCodes.isEmpty()) {
            return Collections.emptySet();
        }
        List<UUID> ids = certRepository.findTechnicianIdsWithCurrentCertifications(
                requiredTypeCodes, atDate, requiredTypeCodes.size());
        return new HashSet<>(ids);
    }

    // ---- CertificationGuardPort ---------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<String> assertAssignable(UUID technicianId,
                                          Set<String> requiredTypeCodes,
                                          LocalDate atDate) {
        if (requiredTypeCodes == null || requiredTypeCodes.isEmpty()) {
            return Collections.emptyList();
        }

        // Load all required type entities to determine regulated vs advisory
        List<CertificationTypeEntity> requiredTypes =
                typeRepository.findByCodeInAndActiveTrue(requiredTypeCodes);

        Map<String, CertificationTypeEntity> byCode = requiredTypes.stream()
                .collect(Collectors.toMap(CertificationTypeEntity::getCode, t -> t));

        List<String> missingRegulated = new ArrayList<>();
        List<String> missingAdvisory  = new ArrayList<>();

        for (String code : requiredTypeCodes) {
            CertificationTypeEntity type = byCode.get(code);
            if (type == null) {
                // Unknown type code — treat as regulated fail (fail-closed)
                log.warn("certification_guard_unknown_type type_code={} technician_id={}", code, technicianId);
                missingRegulated.add(code);
                continue;
            }
            boolean current;
            try {
                current = certRepository.existsCurrentByTechnicianIdAndTypeCode(
                        technicianId, code, atDate);
            } catch (Exception e) {
                log.error("certification_guard_eval_error type_code={} technician_id={} reason={}",
                        code, technicianId, e.getMessage());
                current = false; // fail-closed
            }
            if (!current) {
                if (type.isRegulated()) {
                    missingRegulated.add(code);
                } else {
                    missingAdvisory.add(code);
                }
            }
        }

        if (!missingRegulated.isEmpty()) {
            throw new CertificationNotCurrentException(missingRegulated);
        }
        return missingAdvisory;
    }

    // ---- Certification type admin -------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    public CertificationTypeResponse createCertificationType(CertificationTypeRequest request) {
        if (typeRepository.existsByCode(request.code())) {
            throw new ConflictException("Certification type code already exists: " + request.code());
        }
        CertificationTypeEntity entity = new CertificationTypeEntity(
                request.code(), request.displayName(), request.regulated(),
                request.defaultValidityMonths());
        typeRepository.save(entity);
        return toTypeResponse(entity);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public PagedResponse<CertificationTypeResponse> listCertificationTypes(Pageable pageable) {
        Page<CertificationTypeEntity> page = typeRepository.findAll(pageable);
        List<CertificationTypeResponse> data = page.getContent().stream()
                .map(this::toTypeResponse)
                .collect(Collectors.toList());
        int pageNum = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();
        PageMeta meta = PageMeta.of(pageNum, pageSize, page.getTotalElements());
        String nextLink = page.hasNext()
                ? "/api/v1/certification-types?page=" + (pageNum + 1) + "&size=" + pageSize : null;
        String prevLink = pageNum > 0
                ? "/api/v1/certification-types?page=" + (pageNum - 1) + "&size=" + pageSize : null;
        return PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CertificationTypeResponse updateCertificationType(UUID id, CertificationTypeRequest request) {
        CertificationTypeEntity entity = typeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CertificationType", id.toString()));
        entity.setDisplayName(request.displayName());
        entity.setRegulated(request.regulated());
        entity.setDefaultValidityMonths(request.defaultValidityMonths());
        return toTypeResponse(entity);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CertificationTypeResponse deactivateCertificationType(UUID id) {
        CertificationTypeEntity entity = typeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CertificationType", id.toString()));
        entity.setActive(false);
        return toTypeResponse(entity);
    }

    // ---- Technician certification upsert ------------------------------------

    /**
     * Batch-upsert certifications for a technician.
     * Validates ALL rows first; if any row fails, NOTHING is persisted.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<CertificationRowResult> upsertCertifications(UUID technicianId,
                                                              List<CertificationItemRequest> items) {
        if (items.size() > MAX_BATCH_SIZE) {
            throw new BusinessGuardException(
                    "Batch size " + items.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }
        technicianRepository.findById(technicianId)
                .orElseThrow(() -> new NotFoundException("Technician", technicianId.toString()));

        // ---- Phase 1: validate all rows ----------------------------------------
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CertificationItemRequest item = items.get(i);
            Optional<CertificationTypeEntity> typeOpt = typeRepository.findByCode(item.typeCode());
            if (typeOpt.isEmpty()) {
                errors.add("row " + i + ": unknown type code '" + item.typeCode() + "'");
                continue;
            }
            if (!typeOpt.get().isActive()) {
                errors.add("row " + i + ": certification type '" + item.typeCode()
                        + "' is inactive — cannot issue new certifications");
                continue;
            }
            if (item.issuedOn() == null) {
                errors.add("row " + i + ": issuedOn is required");
                continue;
            }
            if (item.expiresOn() != null && item.expiresOn().isBefore(item.issuedOn())) {
                errors.add("row " + i + ": expiresOn must be on or after issuedOn");
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessGuardException("Batch validation failed: " + String.join("; ", errors));
        }

        // ---- Phase 2: persist --------------------------------------------------
        List<CertificationRowResult> results = new ArrayList<>();
        for (CertificationItemRequest item : items) {
            CertificationTypeEntity type = typeRepository.findByCode(item.typeCode()).orElseThrow();
            Optional<TechnicianCertification> existing =
                    certRepository.findByTechnicianIdAndCertificationTypeIdAndActiveTrue(
                            technicianId, type.getId());
            String outcome;
            if (existing.isPresent()) {
                // Deactivate old and create new (preserve audit trail)
                existing.get().deactivate();
                certRepository.save(existing.get());
                outcome = "UPDATED";
            } else {
                outcome = "CREATED";
            }
            TechnicianCertification cert = new TechnicianCertification(
                    technicianId, type.getId(),
                    item.certificateReference(),
                    item.issuedOn(), item.expiresOn(),
                    item.issuingBody());
            certRepository.save(cert);
            results.add(new CertificationRowResult(item.typeCode(), outcome, null));
        }

        publishCertificationEvent(technicianId);
        return results;
    }

    /** Returns all certifications (current and expired) for the technician. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER','TECHNICIAN')")
    public List<CertificationSummary> allCertifications(UUID technicianId, LocalDate atDate) {
        List<TechnicianCertification> certs =
                certRepository.findByTechnicianId(technicianId);
        // Only include new-schema rows (certification_type_id is not null)
        List<TechnicianCertification> newSchema = certs.stream()
                .filter(c -> c.getCertificationTypeId() != null)
                .collect(Collectors.toList());

        Set<UUID> typeIds = newSchema.stream()
                .map(TechnicianCertification::getCertificationTypeId)
                .collect(Collectors.toSet());
        Map<UUID, CertificationTypeEntity> typeById = typeIds.isEmpty()
                ? Collections.emptyMap()
                : typeRepository.findAllById(typeIds).stream()
                        .collect(Collectors.toMap(CertificationTypeEntity::getId, t -> t));

        return newSchema.stream()
                .map(c -> toSummary(c, typeById.get(c.getCertificationTypeId()), atDate))
                .collect(Collectors.toList());
    }

    // ---- Helpers ------------------------------------------------------------

    private CertificationSummary toSummary(TechnicianCertification c,
                                            CertificationTypeEntity type,
                                            LocalDate atDate) {
        boolean current = c.getExpiresOn() == null || !c.getExpiresOn().isBefore(atDate);
        Long daysUntilExpiry = null;
        if (c.getExpiresOn() != null) {
            daysUntilExpiry = ChronoUnit.DAYS.between(atDate, c.getExpiresOn());
        }
        String typeCode        = type != null ? type.getCode()        : "(unknown)";
        String typeDisplayName = type != null ? type.getDisplayName() : "(unknown)";
        boolean regulated      = type != null && type.isRegulated();
        return new CertificationSummary(
                c.getId(), typeCode, typeDisplayName, regulated,
                c.getCertificateReference(), c.getIssuedOn(), c.getExpiresOn(),
                current, daysUntilExpiry);
    }

    private CertificationTypeResponse toTypeResponse(CertificationTypeEntity e) {
        return new CertificationTypeResponse(
                e.getId(), e.getCode(), e.getDisplayName(),
                e.isRegulated(), e.getDefaultValidityMonths(), e.isActive());
    }

    private void publishCertificationEvent(UUID technicianId) {
        var scope = accessScope.get();
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "TechnicianCertificationChanged",
                "TECHNICIAN",
                technicianId,
                Instant.now(),
                MDC.get("traceId"),
                scope != null ? scope.userId() : null,
                Map.of("technicianId", technicianId.toString())));
    }
}
