package com.fieldservice.privacy.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.ClassificationAdminPort;
import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.UpdateClassificationRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring-cached implementation of the classification registry.
 *
 * <p>Package-private — callers use {@link ClassificationRegistry} or
 * {@link ClassificationAdminPort} interfaces only.
 *
 * <p>Cache name {@code "classifications"}: uses the shared Caffeine {@link org.springframework.cache.CacheManager}
 * configured in the app module. The entire findAll result is cached under a single key and
 * evicted on every successful update.
 */
@Service
@Transactional(readOnly = true)
class ClassificationRegistryImpl implements ClassificationRegistry, ClassificationAdminPort {

    private static final Logger log = LoggerFactory.getLogger(ClassificationRegistryImpl.class);

    static final int MAX_PAGE_SIZE = 50;
    static final String CACHE_NAME = "classifications";

    private final ClassificationRepository repository;
    private final DomainEventPublisher eventPublisher;

    ClassificationRegistryImpl(ClassificationRepository repository,
                                DomainEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    // ── ClassificationRegistry reads ────────────────────────────────────────

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'entity:' + #entityName")
    public Optional<ClassificationView> findByEntity(String entityName) {
        return repository.findByEntityNameAndFieldNameIsNull(entityName)
                .map(ClassificationEntity::toView);
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'field:' + #entityName + ':' + #fieldName")
    public Optional<ClassificationView> findByEntityAndField(String entityName,
                                                              @Nullable String fieldName) {
        if (fieldName == null) {
            return repository.findByEntityNameAndFieldNameIsNull(entityName)
                    .map(ClassificationEntity::toView);
        }
        return repository.findByEntityNameAndFieldName(entityName, fieldName)
                .map(ClassificationEntity::toView);
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'tier:' + #tier")
    public List<ClassificationView> findByTier(ClassificationTier tier) {
        return repository.findByTierOrderByEntityNameAscFieldNameAsc(tier)
                .stream().map(ClassificationEntity::toView).toList();
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "'all'")
    public List<ClassificationView> findAll() {
        return repository.findAllByOrderByEntityNameAscFieldNameAsc()
                .stream().map(ClassificationEntity::toView).toList();
    }

    // ── ClassificationAdminPort ──────────────────────────────────────────────

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public PagedResponse<ClassificationView> listClassifications(
            @Nullable ClassificationTier tierFilter,
            PageQuery pageQuery,
            HttpServletRequest request) {

        int size = Math.min(pageQuery.size() <= 0 ? 20 : pageQuery.size(), MAX_PAGE_SIZE);
        int page = Math.max(pageQuery.page(), 0);

        Sort sort = Sort.by(Sort.Direction.ASC, "entityName", "fieldName", "id");
        PageRequest pageable = PageRequest.of(page, size, sort);

        Page<ClassificationEntity> resultPage;
        if (tierFilter != null) {
            resultPage = repository.findByTierOrderByEntityNameAscFieldNameAscIdAsc(tierFilter, pageable);
        } else {
            resultPage = repository.findAllByOrderByEntityNameAscFieldNameAscIdAsc(pageable);
        }

        List<ClassificationView> records = resultPage.getContent()
                .stream().map(ClassificationEntity::toView).toList();

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
    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    public ClassificationView updateClassification(UUID id, UpdateClassificationRequest request) {
        ClassificationEntity entity = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("DataClassification", id));

        if (!entity.getVersion().equals(request.version())) {
            throw new ConflictException(
                    "DataClassification", id,
                    "Stale version: expected " + entity.getVersion() + " but got " + request.version());
        }

        String actor = resolveActor();
        entity.applyUpdate(request.tier(), request.lawfulBasisNote(),
                request.handlingNotes(), actor);

        ClassificationView saved = repository.save(entity).toView();

        ClassificationChangedPayload payload = new ClassificationChangedPayload(
                saved.id(), saved.entityName(), saved.fieldName(),
                saved.tier(), "UPDATED");

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                ClassificationChangedPayload.EVENT_TYPE,
                ClassificationChangedPayload.AGGREGATE_TYPE,
                saved.id(),
                Instant.now(),
                MDC.get("traceId"),
                resolveActorUuid(),
                PiiRedactionUtility.toPayloadMap(payload)));

        log.info("classification id={} entityName={} tier={} actor={} — updated",
                saved.id(), saved.entityName(), saved.tier(), actor);

        return saved;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @Nullable
    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    @Nullable
    private UUID resolveActorUuid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
