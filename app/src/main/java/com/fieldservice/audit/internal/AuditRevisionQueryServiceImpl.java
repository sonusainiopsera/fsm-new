package com.fieldservice.audit.internal;

import com.fieldservice.audit.api.AuditRevisionQueryService;
import com.fieldservice.platform.exception.NotFoundException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only implementation of {@link AuditRevisionQueryService}.
 *
 * <p>Entity type validation against the allow-list occurs before any query execution.
 * No write path exists in this class or its dependencies.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('ADMIN', 'COMPLIANCE_REVIEWER')")
public class AuditRevisionQueryServiceImpl implements AuditRevisionQueryService {

    @Value("${app.audit.retention-days:365}")
    private int retentionDays;

    private final AuditRevisionRepository repository;
    private final RevisionDiffCalculator diffCalculator;
    private final EntityManager entityManager;

    public AuditRevisionQueryServiceImpl(
            AuditRevisionRepository repository,
            RevisionDiffCalculator diffCalculator,
            EntityManager entityManager) {
        this.repository = repository;
        this.diffCalculator = diffCalculator;
        this.entityManager = entityManager;
    }

    @Override
    public RevisionPage search(RevisionFilter filter, int page, int size) {
        validateFilter(filter);
        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);
        int offset = page * effectiveSize;

        String actorId = filter.actorUserId() != null ? filter.actorUserId().toString() : null;

        List<AuditRevisionRepository.RevisionRow> rows = repository.search(
                filter.entityType(),
                filter.entityId(),
                actorId,
                filter.from(),
                filter.to(),
                filter.revFrom(),
                filter.revTo(),
                offset,
                effectiveSize);

        boolean hasNext = rows.size() > effectiveSize;
        List<AuditRevisionRepository.RevisionRow> pageRows = hasNext
                ? rows.subList(0, effectiveSize)
                : rows;

        long total = repository.count(
                filter.entityType(), filter.entityId(), actorId,
                filter.from(), filter.to(), filter.revFrom(), filter.revTo());

        List<RevisionEntry> entries = pageRows.stream()
                .map(r -> new RevisionEntry(
                        r.revisionNumber(),
                        r.revisionTimestamp(),
                        r.actor(),
                        r.entityType(),
                        r.entityId(),
                        r.changeType(),
                        List.of()))
                .toList();

        return new RevisionPage(entries, new PageMeta(page, effectiveSize, total), hasNext);
    }

    @Override
    public Optional<RevisionDetail> getRevisionDiff(int revisionNumber, String entityType, UUID entityId) {
        if (!AuditEntityMetadata.isAllowed(entityType) || entityType == null) {
            return Optional.empty();
        }

        Class<?> entityClass = resolveEntityClass(entityType);
        if (entityClass == null) return Optional.empty();

        AuditReader reader = AuditReaderFactory.get(entityManager);
        com.fieldservice.platform.audit.AuditRevisionEntity revInfo;
        try {
            revInfo = reader.findRevision(com.fieldservice.platform.audit.AuditRevisionEntity.class, revisionNumber);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (revInfo == null) return Optional.empty();

        List<FieldDiff> fields = diffCalculator.compute(entityClass, entityId, revisionNumber, entityType);

        return Optional.of(new RevisionDetail(
                revisionNumber,
                revInfo.getRevisionInstant(),
                revInfo.getActorUserId(),
                entityType,
                entityId,
                fields
        ));
    }

    private void validateFilter(RevisionFilter filter) {
        if (filter.entityType() != null && !AuditEntityMetadata.isAllowed(filter.entityType())) {
            throw new IllegalArgumentException(
                    "Unknown entityType '" + filter.entityType() + "'. Allowed: " +
                    AuditEntityMetadata.allowedTypes());
        }
    }

    private Class<?> resolveEntityClass(String entityType) {
        return switch (entityType) {
            case "WorkOrder"   -> com.fieldservice.domain.workorder.WorkOrder.class;
            case "AppUser"     -> com.fieldservice.domain.user.AppUser.class;
            case "Site"        -> com.fieldservice.domain.site.Site.class;
            case "Assignment"  -> com.fieldservice.domain.assignment.Assignment.class;
            case "SlaPolicy"   -> com.fieldservice.sla.internal.SlaBreachEntity.class;
            case "Customer"    -> com.fieldservice.domain.customer.Customer.class;
            case "Asset"       -> com.fieldservice.domain.asset.Asset.class;
            default            -> null;
        };
    }
}
