package com.fieldservice.privacy.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.JdbcSchedulingLock;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarRequestView;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectRef;
import com.fieldservice.privacy.api.SubjectSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Asynchronous worker job that assembles DSAR export artifacts.
 *
 * <p>Runs on the {@code worker} Spring profile only, guarded by a distributed lock.
 * Processes all VERIFIED requests, claims each, aggregates all registered
 * {@link SubjectDataProvider} implementations, writes the manifest and export JSON,
 * persists the artifact, and transitions the request to FULFILLED.
 *
 * <p>Idempotency: keyed on {@code dsar_request.id} and {@code attempt_count}.
 * A partial artifact from a crashed run is replaced on the next execution.
 * Zero-row sections are always included in the manifest so completeness is provable.
 */
@Component
@Profile("worker")
class SubjectExportJob {

    private static final Logger log = LoggerFactory.getLogger(SubjectExportJob.class);
    private static final String LOCK_NAME       = "dsar-export-assembly";
    private static final String SYSTEM_ACTOR    = "SYSTEM";
    private static final String AGGREGATE_TYPE  = "DSAR_REQUEST";

    private final DsarRequestRepository        requestRepository;
    private final DsarExportArtifactRepository artifactRepository;
    private final JdbcSchedulingLock           schedulingLock;
    private final DsarProperties               properties;
    private final Clock                        clock;
    private final PlatformTransactionManager   txManager;
    private final DomainEventPublisher         eventPublisher;
    private final List<SubjectDataProvider>    providers;
    private final ObjectMapper                 objectMapper;

    SubjectExportJob(DsarRequestRepository requestRepository,
                     DsarExportArtifactRepository artifactRepository,
                     JdbcSchedulingLock schedulingLock,
                     DsarProperties properties,
                     Clock clock,
                     PlatformTransactionManager txManager,
                     DomainEventPublisher eventPublisher,
                     List<SubjectDataProvider> providers,
                     ObjectMapper objectMapper) {
        this.requestRepository  = requestRepository;
        this.artifactRepository = artifactRepository;
        this.schedulingLock     = schedulingLock;
        this.properties         = properties;
        this.clock              = clock;
        this.txManager          = txManager;
        this.eventPublisher     = eventPublisher;
        this.providers          = providers;
        this.objectMapper       = objectMapper;
    }

    @Scheduled(cron = "${app.privacy.dsar.export-cron:0 */5 * * * *}")
    public void runExportSweep() {
        schedulingLock.runIfLeader(LOCK_NAME, holderName(),
                properties.getExportLockLeaseSecs(), this::doExportSweep);
    }

    void doExportSweep() {
        List<DsarRequestEntity> verified = requestRepository.findAllVerifiedOrderByDueAt();
        log.info("dsar_export_sweep_start count={}", verified.size());

        for (DsarRequestEntity request : verified) {
            try {
                processRequest(request);
            } catch (Exception e) {
                log.error("dsar_export_failed requestId={} error={}", request.getId(), e.getMessage(), e);
                // Continue to next request — do not abort the entire sweep
            }
        }

        log.info("dsar_export_sweep_done");
    }

    private void processRequest(DsarRequestEntity request) {
        UUID requestId = request.getId();
        log.info("dsar_export_assembling requestId={} subjectType={} attempt={}",
                requestId, request.getSubjectType(), request.getAttemptCount() + 1);

        // Claim: VERIFIED → IN_PROGRESS in a transaction
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            DsarRequestEntity fresh = requestRepository.findById(requestId)
                    .orElseThrow(() -> new IllegalStateException("Request disappeared: " + requestId));
            if (fresh.getState() != DsarState.VERIFIED) {
                log.info("dsar_export_skip_not_verified requestId={} state={}",
                        requestId, fresh.getState());
                return;
            }
            // Inline claim transition (SYSTEM actor bypasses role check)
            fresh.applyTransition(DsarState.IN_PROGRESS, DsarEvent.CLAIM,
                    null, null, clock.instant(), SYSTEM_ACTOR);
            requestRepository.save(fresh);
        });

        // Assemble export outside the claim transaction so it doesn't hold a long-running TX
        SubjectRef ref = new SubjectRef(request.getSubjectType(), request.getSubjectId());
        List<SubjectSection> sections = aggregateProviders(ref);

        String manifestJson;
        String exportJson;
        try {
            List<Map<String, Object>> manifestList = sections.stream().map(s -> Map.<String, Object>of(
                    "sectionName",   s.sectionName(),
                    "sourceModule",  s.sourceModule(),
                    "schemaVersion", s.schemaVersion(),
                    "rowCount",      s.rowCount()
            )).toList();
            manifestJson = objectMapper.writeValueAsString(manifestList);

            Map<String, Object> exportDoc = Map.of(
                    "requestId",   requestId.toString(),
                    "subjectType", ref.subjectType(),
                    "subjectId",   ref.subjectId().toString(),
                    "generatedAt", clock.instant().toString(),
                    "manifest",    manifestList,
                    "sections",    sections.stream().map(s -> Map.of(
                            "sectionName", s.sectionName(),
                            "rows",        s.rows()
                    )).toList()
            );
            exportJson = objectMapper.writeValueAsString(exportDoc);
        } catch (JsonProcessingException e) {
            log.error("dsar_export_serialization_failed requestId={}", requestId, e);
            return;
        }

        long byteSize = exportJson.getBytes(StandardCharsets.UTF_8).length;

        // Persist artifact and transition to FULFILLED atomically
        tx.executeWithoutResult(s -> {
            DsarRequestEntity fresh = requestRepository.findById(requestId)
                    .orElseThrow(() -> new IllegalStateException("Request disappeared: " + requestId));

            if (fresh.getState() != DsarState.IN_PROGRESS) {
                log.warn("dsar_export_skip_not_in_progress requestId={} state={}",
                        requestId, fresh.getState());
                return;
            }

            // Idempotency: replace any existing artifact for this request
            artifactRepository.findByDsarRequestId(requestId)
                    .ifPresent(existing -> artifactRepository.delete(existing));

            DsarExportArtifactEntity artifact = DsarExportArtifactEntity.create(
                    UuidV7.generate(), requestId,
                    "dsar/" + requestId + "/export.json",
                    manifestJson, exportJson, byteSize, clock.instant());
            artifactRepository.save(artifact);

            fresh.applyTransition(DsarState.FULFILLED, DsarEvent.FULFIL,
                    null, null, clock.instant(), SYSTEM_ACTOR);
            requestRepository.save(fresh);

            publishFulfilmentEvent(requestId);
        });

        log.info("dsar_export_fulfilled requestId={} byteSize={} sections={}",
                requestId, byteSize, sections.size());
    }

    private List<SubjectSection> aggregateProviders(SubjectRef ref) {
        List<SubjectSection> sections = new ArrayList<>();
        for (SubjectDataProvider provider : providers) {
            if (!provider.supportedSubjectTypes().contains(ref.subjectType())
                    && !provider.supportedSubjectTypes().contains("*")) {
                // Provider does not handle this subject type — add an empty section
                sections.add(SubjectSection.empty(provider.sectionName() + ".not_applicable",
                        provider.sectionName()));
                continue;
            }
            try {
                SubjectSection section = provider.collect(ref);
                // Guard: providers must never return null
                sections.add(section != null ? section
                        : SubjectSection.empty(provider.sectionName(), provider.sectionName()));
            } catch (Exception e) {
                log.error("dsar_provider_error section={} subjectType={} error={}",
                        provider.sectionName(), ref.subjectType(), e.getMessage());
                // Record the section as errored rather than failing the whole export
                sections.add(new SubjectSection(
                        provider.sectionName(), provider.sectionName(), 1, -1,
                        List.of(Map.of("error", e.getMessage()))));
            }
        }
        return sections;
    }

    private void publishFulfilmentEvent(UUID requestId) {
        try {
            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(), "DSAR_FULFILLED", AGGREGATE_TYPE, requestId,
                    clock.instant(), null, null,
                    Map.of("state", DsarState.FULFILLED.name())));
        } catch (Exception e) {
            log.error("dsar_fulfil_event_failed requestId={} error={}", requestId, e.getMessage());
        }
    }

    private static String holderName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
