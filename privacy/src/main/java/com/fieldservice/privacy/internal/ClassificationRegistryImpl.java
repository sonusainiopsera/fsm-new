package com.fieldservice.privacy.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationService;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class ClassificationRegistryImpl implements ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationRegistryImpl.class);

    static final String CACHE_NAME = "classifications";

    private final DataClassificationRepository repository;
    private final DomainEventPublisher         eventPublisher;

    ClassificationRegistryImpl(DataClassificationRepository repository,
                                DomainEventPublisher eventPublisher) {
        this.repository     = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'entity:' + #entityName")
    public Optional<ClassificationView> findByEntity(String entityName) {
        return repository.findByEntityNameAndFieldNameIsNull(entityName)
                .map(DataClassificationEntity::toView);
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'field:' + #entityName + ':' + #fieldName")
    public Optional<ClassificationView> findByEntityAndField(String entityName, String fieldName) {
        return repository.findByEntityNameAndFieldName(entityName, fieldName)
                .map(DataClassificationEntity::toView);
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'tier:' + #tier.name()")
    public List<ClassificationView> findByTier(ClassificationTier tier) {
        return repository.findByTier(tier).stream()
                .map(DataClassificationEntity::toView)
                .toList();
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'all'")
    public List<ClassificationView> findAll() {
        return repository.findAll().stream()
                .map(DataClassificationEntity::toView)
                .toList();
    }

    @Override
    public Page<ClassificationView> listPage(Pageable pageable, ClassificationTier tierFilter) {
        Page<DataClassificationEntity> page = tierFilter != null
                ? repository.findByTier(tierFilter, pageable)
                : repository.findAll(pageable);
        return page.map(DataClassificationEntity::toView);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    public ClassificationView update(UUID id,
                                      ClassificationTier tier,
                                      String lawfulBasisNote,
                                      String handlingNotes,
                                      int expectedVersion,
                                      String updatedBy) {
        DataClassificationEntity entity = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("DataClassification", id.toString()));

        if (entity.getVersion() != expectedVersion) {
            throw new ConflictException(
                    "Stale version for DataClassification " + id
                    + ": expected " + expectedVersion + " but current is " + entity.getVersion());
        }

        entity.setTier(tier);
        entity.setLawfulBasisNote(lawfulBasisNote);
        entity.setHandlingNotes(handlingNotes);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(updatedBy);

        DataClassificationEntity saved = repository.save(entity);

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "CLASSIFICATION_CHANGED",
                "DATA_CLASSIFICATION",
                id,
                Instant.now(),
                resolveTraceId(),
                resolveActorId(),
                new ClassificationChangedPayload(id, entity.getEntityName(), entity.getFieldName(),
                        tier.name(), updatedBy)));

        log.info("classification_updated id={} entity={} field={} new_tier={} actor={}",
                id, entity.getEntityName(), entity.getFieldName(), tier, updatedBy);

        return saved.toView();
    }

    private static String resolveTraceId() {
        String tid = org.slf4j.MDC.get("traceId");
        return (tid != null && !tid.isBlank()) ? tid : null;
    }

    private static UUID resolveActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Outbox event payload — no PII values, only metadata names and tier. */
    record ClassificationChangedPayload(
            UUID   classificationId,
            String entityName,
            String fieldName,
            String newTier,
            String updatedBy) {}
}
