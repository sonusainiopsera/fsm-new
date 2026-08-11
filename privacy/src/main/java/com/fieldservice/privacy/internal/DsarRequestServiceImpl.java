package com.fieldservice.privacy.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.IllegalTransitionException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.privacy.api.CreateDsarRequest;
import com.fieldservice.privacy.api.DsarAdminPort;
import com.fieldservice.privacy.api.DsarRequestView;
import com.fieldservice.privacy.api.DsarTransitionRequest;
import com.fieldservice.privacy.api.ExportResponse;
import com.fieldservice.privacy.api.FulfillmentMetrics;
import com.fieldservice.workorder.lifecycle.GuardResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implements {@link DsarAdminPort} — package-private, accessed only via the port interface.
 *
 * <p>Every public method requires {@code PRIVACY_ADMIN} or {@code ADMIN} role.
 * Non-privacy roles receive 403 with no existence disclosure of the request ID.
 */
@Service
@Transactional(readOnly = true)
class DsarRequestServiceImpl implements DsarAdminPort {

    private static final Logger log = LoggerFactory.getLogger(DsarRequestServiceImpl.class);

    private final DsarRequestRepository requestRepository;
    private final DsarExportArtifactRepository artifactRepository;
    private final Map<String, DsarTransitionGuard> guardsById;
    private final ExportStoragePort storage;
    private final DsarProperties properties;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    DsarRequestServiceImpl(DsarRequestRepository requestRepository,
                           DsarExportArtifactRepository artifactRepository,
                           List<DsarTransitionGuard> guards,
                           ExportStoragePort storage,
                           DsarProperties properties,
                           DomainEventPublisher eventPublisher,
                           Clock clock) {
        this.requestRepository = requestRepository;
        this.artifactRepository = artifactRepository;
        this.guardsById = guards.stream()
                .collect(Collectors.toMap(DsarTransitionGuard::guardId, Function.identity()));
        this.storage = storage;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public DsarRequestView createRequest(CreateDsarRequest req) {
        Instant now = Instant.now(clock);
        Instant dueAt = now.plus(properties.getSlaDays(), ChronoUnit.DAYS);

        DsarRequest entity = new DsarRequest(
                req.requestType(), req.subjectType(), req.subjectId(),
                now, dueAt, req.notes());
        entity = requestRepository.save(entity);

        publishStateEvent(entity, "DsarRequestCreated", now);
        log.info("dsar.created: id={} type={} subject={}/{}", entity.getId(),
                entity.getRequestType(), entity.getSubjectType(), entity.getSubjectId());
        return toView(entity, now);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public DsarRequestView applyTransition(UUID id, DsarTransitionRequest req) {
        DsarRequest entity = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("DsarRequest", id));

        DsarEvent event = parseEvent(req.event());
        DsarTransitionDescriptor descriptor = DsarTransitionTable.resolve(entity.getState(), event)
                .orElseThrow(() -> new IllegalTransitionException(
                        entity.getState().name(), event.name()));

        DsarTransitionContext context = new DsarTransitionContext(
                req.verificationMethod(), req.note());

        for (String guardId : descriptor.guardIds()) {
            DsarTransitionGuard guard = guardsById.get(guardId);
            if (guard == null) {
                throw new BusinessGuardException("GUARD_NOT_FOUND",
                        "Transition guard '" + guardId + "' is registered in the table but has no bean");
            }
            GuardResult result = guard.evaluate(entity, event, context);
            switch (result) {
                case GuardResult.Satisfied s -> { /* proceed */ }
                case GuardResult.Refused r -> throw new BusinessGuardException(r.code(), r.message());
            }
        }

        String outcome = terminalOutcome(descriptor.toState(), entity);
        entity.applyTransition(descriptor.toState(), req.verificationMethod(), req.note(), outcome);
        entity = requestRepository.save(entity);

        Instant now = Instant.now(clock);
        publishStateEvent(entity, "DsarStateChanged", now);
        log.info("dsar.transition: id={} from={} event={} to={}", id,
                entity.getState() == descriptor.toState()
                        ? "prev" : entity.getState().name(),
                event, descriptor.toState());
        return toView(entity, now);
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public PagedResponse<DsarRequestView> listRequests(PageQuery pageQuery,
                                                        @Nullable String stateFilter,
                                                        HttpServletRequest servletRequest) {
        int size = Math.min(pageQuery.size() <= 0 ? 20 : pageQuery.size(), properties.getMaxPageSize());
        int page = Math.max(pageQuery.page(), 0);

        Instant now = Instant.now(clock);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        org.springframework.data.domain.Page<DsarRequest> resultPage;
        if (stateFilter != null && !stateFilter.isBlank()) {
            DsarState state = parseState(stateFilter);
            resultPage = requestRepository.findByStateOrderByDueAtAscIdAsc(state, pageable);
        } else {
            resultPage = requestRepository.findAllByOrderByDueAtAscIdAsc(pageable);
        }

        List<DsarRequestView> records = resultPage.getContent()
                .stream().map(r -> toView(r, now)).toList();

        PageMeta meta = PageMeta.of(resultPage.getNumber(), resultPage.getSize(),
                resultPage.getTotalElements());
        String base = UriComponentsBuilder.fromRequestUri(servletRequest).toUriString();
        String next = resultPage.hasNext()
                ? base + "?page=" + (page + 1) + "&size=" + size : null;
        String prev = page > 0
                ? base + "?page=" + (page - 1) + "&size=" + size : null;
        return PagedResponse.of(records, meta, PageLinks.of(next, prev));
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public DsarRequestView getRequest(UUID id) {
        DsarRequest entity = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("DsarRequest", id));
        return toView(entity, Instant.now(clock));
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ExportResponse getExport(UUID id) {
        DsarRequest req = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("DsarRequest", id));

        if (req.getState() == DsarState.RECEIVED
                || req.getState() == DsarState.IDENTITY_PENDING
                || req.getState() == DsarState.VERIFIED) {
            throw new BusinessGuardException("EXPORT_NOT_READY",
                    "Export is not available for request " + id
                    + " in state " + req.getState()
                    + ". Request must be IN_PROGRESS or FULFILLED.");
        }

        DsarExportArtifact artifact = artifactRepository.findByDsarRequestId(id)
                .orElseThrow(() -> new NotFoundException("DsarExportArtifact for request", id));

        int expirySeconds = properties.getDownloadUrlExpirySeconds();
        String downloadUrl = storage.generateDownloadUrl(artifact.getStorageKey(), expirySeconds);

        List<ExportResponse.ManifestEntry> manifestEntries = parseManifest(artifact.getManifest());

        log.info("dsar.export.download-granted: requestId={} artifactId={}", id, artifact.getId());
        return new ExportResponse(artifact.getId(), manifestEntries, downloadUrl, expirySeconds);
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public FulfillmentMetrics getFulfillmentMetrics() {
        List<DsarRequest> all = requestRepository.findAll();
        Instant now = Instant.now(clock);

        long totalClosed = 0, fulfilledInTime = 0, fulfilledLate = 0;
        Map<String, Long> openByRemaining = new TreeMap<>();

        for (DsarRequest r : all) {
            DsarState state = r.getState();
            if (state == DsarState.FULFILLED || state == DsarState.REJECTED
                    || state == DsarState.WITHDRAWN) {
                totalClosed++;
                if (state == DsarState.FULFILLED) {
                    long elapsed = ChronoUnit.DAYS.between(r.getSubmittedAt(),
                            r.getUpdatedAt() != null ? r.getUpdatedAt() : now);
                    if (elapsed <= properties.getSlaDays()) {
                        fulfilledInTime++;
                    } else {
                        fulfilledLate++;
                    }
                }
            } else {
                long remaining = ChronoUnit.DAYS.between(now, r.getDueAt());
                String bucket = remaining <= 0 ? "overdue"
                        : remaining <= 5 ? "0-5"
                        : remaining <= 14 ? "6-14" : "15+";
                openByRemaining.merge(bucket, 1L, Long::sum);
            }
        }

        double rate = totalClosed == 0 ? 100.0
                : Math.round((fulfilledInTime * 1000.0) / totalClosed) / 10.0;
        return new FulfillmentMetrics(totalClosed, fulfilledInTime, fulfilledLate, rate,
                openByRemaining);
    }

    // ── Package-private helpers used by SubjectExportJob ─────────────────────────

    DsarRequest loadVerified(UUID id) {
        DsarRequest req = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("DsarRequest", id));
        if (req.getState() != DsarState.VERIFIED && req.getState() != DsarState.IN_PROGRESS) {
            throw new BusinessGuardException("NOT_VERIFIED",
                    "Request " + id + " is in state " + req.getState()
                    + "; only VERIFIED or IN_PROGRESS requests can be assembled");
        }
        return req;
    }

    void transitionToInProgress(DsarRequest req) {
        DsarTransitionDescriptor descriptor = DsarTransitionTable.resolve(
                req.getState(), DsarEvent.START_PROCESSING).orElse(null);
        if (descriptor == null) return;
        req.applyTransition(DsarState.IN_PROGRESS, null, null, null);
        requestRepository.save(req);
        publishStateEvent(req, "DsarStateChanged", Instant.now(clock));
    }

    void saveArtifactAndFulfill(DsarRequest req, DsarExportArtifact artifact) {
        // Atomically replace any prior artifact and transition to FULFILLED
        artifactRepository.findByDsarRequestId(req.getId())
                .ifPresent(old -> artifactRepository.delete(old));
        artifactRepository.save(artifact);

        req.applyTransition(DsarState.FULFILLED, null, null, "FULFILLED_IN_TIME");
        requestRepository.save(req);
        publishStateEvent(req, "DsarFulfilled", Instant.now(clock));
        log.info("dsar.fulfilled: id={} artifactId={}", req.getId(), artifact.getId());
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private DsarRequestView toView(DsarRequest entity, Instant now) {
        long remaining = ChronoUnit.DAYS.between(now, entity.getDueAt());
        boolean atRisk = remaining <= properties.getAtRiskThresholdDays();
        return entity.toView(remaining, atRisk);
    }

    private DsarEvent parseEvent(String eventName) {
        try {
            return DsarEvent.valueOf(eventName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessGuardException("UNKNOWN_DSAR_EVENT",
                    "Unknown DSAR event: '" + eventName + "'");
        }
    }

    private DsarState parseState(String stateName) {
        try {
            return DsarState.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessGuardException("UNKNOWN_DSAR_STATE",
                    "Unknown DSAR state: '" + stateName + "'");
        }
    }

    @Nullable
    private static String terminalOutcome(DsarState toState, DsarRequest entity) {
        return switch (toState) {
            case REJECTED -> "REJECTED";
            case WITHDRAWN -> "WITHDRAWN";
            default -> null;
        };
    }

    private void publishStateEvent(DsarRequest entity, String eventType, Instant now) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dsarRequestId", entity.getId().toString());
        payload.put("state", entity.getState().name());
        payload.put("requestType", entity.getRequestType());
        payload.put("subjectType", entity.getSubjectType());
        // subjectId deliberately omitted from event payload per PII policy
        eventPublisher.publish(DomainEvent.of(
                eventType, "DsarRequest", entity.getId(), now, null, null, payload));
    }

    @SuppressWarnings("unchecked")
    private List<ExportResponse.ManifestEntry> parseManifest(String manifestJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> entries = mapper.readValue(manifestJson, List.class);
            return entries.stream()
                    .map(e -> new ExportResponse.ManifestEntry(
                            (String) e.get("sectionName"),
                            (String) e.get("sourceModule"),
                            ((Number) e.getOrDefault("rowCount", 0)).intValue()))
                    .toList();
        } catch (Exception e) {
            log.warn("dsar.manifest.parse-error: {}", e.getMessage());
            return List.of();
        }
    }
}
