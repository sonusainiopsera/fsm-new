package com.fieldservice.privacy.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarRequestService;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarRequestView;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.ExportArtifactView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class DsarRequestServiceImpl implements DsarRequestService {

    private static final Logger log = LoggerFactory.getLogger(DsarRequestServiceImpl.class);

    private static final String AGGREGATE_TYPE = "DSAR_REQUEST";

    private final DsarRequestRepository         requestRepository;
    private final DsarExportArtifactRepository  artifactRepository;
    private final DomainEventPublisher          eventPublisher;
    private final DownloadTokenService          downloadTokenService;
    private final DsarProperties               properties;
    private final Clock                         clock;
    private final ZoneId                        zone;
    private final Map<String, DsarTransitionGuard> guardMap;

    DsarRequestServiceImpl(DsarRequestRepository requestRepository,
                            DsarExportArtifactRepository artifactRepository,
                            DomainEventPublisher eventPublisher,
                            DownloadTokenService downloadTokenService,
                            DsarProperties properties,
                            Clock clock,
                            List<DsarTransitionGuard> guards) {
        this.requestRepository  = requestRepository;
        this.artifactRepository = artifactRepository;
        this.eventPublisher     = eventPublisher;
        this.downloadTokenService = downloadTokenService;
        this.properties         = properties;
        this.clock              = clock;
        this.zone               = ZoneId.of(properties.getZone());
        this.guardMap           = guards.stream()
                .collect(Collectors.toMap(DsarTransitionGuard::guardId, g -> g));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public DsarRequestView createRequest(DsarRequestType requestType, String subjectType,
                                          UUID subjectId, String notes, String actor) {
        Instant now   = clock.instant();
        Instant dueAt = now.plus(properties.getRequestDueDays());

        DsarRequestEntity entity = DsarRequestEntity.create(
                UuidV7.generate(), requestType, subjectType, subjectId, now, dueAt, actor);

        DsarRequestEntity saved = requestRepository.save(entity);

        publishEvent("DSAR_CREATED", saved.getId(), actor, Map.of(
                "requestType", requestType.name(),
                "subjectType", subjectType,
                "state",       DsarState.RECEIVED.name()));

        log.info("dsar_created id={} requestType={} subjectType={} actor={}",
                saved.getId(), requestType, subjectType, actor);

        return saved.toView(remainingDays(saved.getDueAt()), isAtRisk(saved.getDueAt()));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public DsarRequestView applyTransition(UUID id, DsarEvent event, String verificationMethod,
                                            String note, int expectedVersion, String actor) {
        DsarRequestEntity entity = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("DsarRequest", id.toString()));

        if (entity.getVersion() != expectedVersion) {
            throw new ConflictException("Stale version for DsarRequest " + id);
        }

        DsarTransitionDescriptor descriptor = DsarTransitionTable.resolve(entity.getState(), event)
                .orElseThrow(() -> new DsarIllegalTransitionException(
                        "Event " + event + " is not legal from state " + entity.getState()
                        + " for DSAR request " + id));

        // Evaluate guards in order — never fail open
        for (String guardId : descriptor.guardIds()) {
            DsarTransitionGuard guard = guardMap.get(guardId);
            if (guard == null) {
                throw new DsarGuardRefusalException("GUARD_NOT_REGISTERED",
                        "Guard '" + guardId + "' is referenced in the transition table but has no implementation");
            }
            DsarGuardResult result = guard.evaluate(entity.getState(), event, entity);
            if (result instanceof DsarGuardResult.Refused refused) {
                throw new DsarGuardRefusalException(refused.code(), refused.message());
            }
        }

        DsarState prevState = entity.getState();
        entity.applyTransition(descriptor.toState(), event, verificationMethod, note,
                               clock.instant(), actor);
        DsarRequestEntity saved = requestRepository.save(entity);

        publishEvent("DSAR_TRANSITION", id, actor, Map.of(
                "fromState", prevState.name(),
                "toState",   descriptor.toState().name(),
                "event",     event.name()));

        log.info("dsar_transition id={} from={} event={} to={} actor={}",
                id, prevState, event, descriptor.toState(), actor);

        return saved.toView(remainingDays(saved.getDueAt()), isAtRisk(saved.getDueAt()));
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public Page<DsarRequestView> listRequests(DsarState stateFilter, Pageable pageable) {
        Page<DsarRequestEntity> page = (stateFilter != null)
                ? requestRepository.findByState(stateFilter, pageable)
                : requestRepository.findAll(pageable);
        return page.map(e -> e.toView(remainingDays(e.getDueAt()), isAtRisk(e.getDueAt())));
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public DsarRequestView getRequest(UUID id) {
        DsarRequestEntity e = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("DsarRequest", id.toString()));
        return e.toView(remainingDays(e.getDueAt()), isAtRisk(e.getDueAt()));
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ExportArtifactView getExport(UUID requestId, String actor) {
        DsarRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("DsarRequest", requestId.toString()));

        if (request.getState() != DsarState.FULFILLED) {
            throw new ExportNotReadyException(
                    "Export is not available for request " + requestId
                    + " in state " + request.getState()
                    + ". The request must be FULFILLED before an export can be downloaded.");
        }

        DsarExportArtifactEntity artifact = artifactRepository.findByDsarRequestId(requestId)
                .orElseThrow(() -> new NotFoundException("DsarExportArtifact", requestId.toString()));

        if (artifact.getDisposedAt() != null) {
            throw new ExportNotReadyException(
                    "Export artifact for request " + requestId + " has been disposed.");
        }

        String downloadToken = downloadTokenService.mint(artifact.getId());
        Instant expiresAt    = downloadTokenService.expiresAt(downloadToken);
        long expiresInSecs   = ChronoUnit.SECONDS.between(clock.instant(), expiresAt);

        log.info("dsar_export_download_granted requestId={} artifactId={} actor={}",
                requestId, artifact.getId(), actor);

        List<ExportArtifactView.ManifestEntry> manifestEntries = parseManifest(artifact.getManifest());

        return new ExportArtifactView(
                artifact.getId(), requestId, manifestEntries,
                downloadToken, expiresAt, expiresInSecs);
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public FulfilmentMetric getFulfilmentMetric() {
        long totalClosed      = requestRepository.countClosed();
        long fulfilledOnTime  = requestRepository.countFulfilledOnTime();
        long openCount        = requestRepository.countOpen();

        double onTimeRate = (totalClosed == 0) ? 1.0 : (double) fulfilledOnTime / totalClosed;

        // Count at-risk open requests (remainingDays <= atRiskThresholdDays)
        Instant atRiskCutoff = clock.instant()
                .plus(properties.getAtRiskThresholdDays(), ChronoUnit.DAYS);
        // Use a simple approach: query open requests and count those with dueAt < atRiskCutoff
        long openAtRisk = requestRepository.findAll().stream()
                .filter(e -> !Set.of(DsarState.FULFILLED, DsarState.REJECTED, DsarState.WITHDRAWN)
                        .contains(e.getState()))
                .filter(e -> e.getDueAt().isBefore(atRiskCutoff))
                .count();

        return new FulfilmentMetric(totalClosed, fulfilledOnTime, onTimeRate, openCount, openAtRisk);
    }

    private long remainingDays(Instant dueAt) {
        long days = ChronoUnit.DAYS.between(clock.instant(), dueAt);
        return Math.max(0, days);
    }

    private boolean isAtRisk(Instant dueAt) {
        return remainingDays(dueAt) <= properties.getAtRiskThresholdDays();
    }

    private void publishEvent(String eventType, UUID aggregateId, String actor,
                              Map<String, Object> payload) {
        try {
            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(), eventType, AGGREGATE_TYPE, aggregateId,
                    clock.instant(), MDC.get("traceId"),
                    actor.equals("SYSTEM") ? null : null,
                    payload));
        } catch (Exception e) {
            log.error("dsar_event_publish_failed eventType={} aggregateId={} error={}",
                    eventType, aggregateId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<ExportArtifactView.ManifestEntry> parseManifest(String manifestJson) {
        if (manifestJson == null) return List.of();
        try {
            ObjectMapper om = new ObjectMapper();
            List<Map<String, Object>> raw = om.readValue(manifestJson, List.class);
            return raw.stream().map(m -> new ExportArtifactView.ManifestEntry(
                    (String) m.get("sectionName"),
                    (String) m.get("sourceModule"),
                    ((Number) m.getOrDefault("rowCount", 0L)).longValue()))
                    .toList();
        } catch (JsonProcessingException e) {
            log.warn("dsar_manifest_parse_error: {}", e.getMessage());
            return List.of();
        }
    }
}
