package com.fieldservice.privacy.internal;

import com.fieldservice.platform.outbox.SchedulingLock;
import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectDataSection;
import com.fieldservice.privacy.api.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Asynchronous worker job that assembles DSAR export bundles.
 *
 * <p>Runs on the {@code worker} Spring profile. Guarded by a distributed
 * {@link SchedulingLock} so exactly one replica runs per cycle.
 *
 * <p>For each request in {@code VERIFIED} state:
 * <ol>
 *   <li>Transitions it to {@code IN_PROGRESS}.</li>
 *   <li>Calls each registered {@link SubjectDataProvider}.</li>
 *   <li>Assembles a manifest JSON document.</li>
 *   <li>Persists an {@link DsarExportArtifact} and transitions to {@code FULFILLED}.</li>
 * </ol>
 *
 * <p>If assembly fails, the artifact is discarded, the request stays {@code IN_PROGRESS},
 * and the next run retries idempotently (keyed on request ID + attempt count).
 */
@Component
@Profile("worker")
public class SubjectExportJob {

    private static final Logger log = LoggerFactory.getLogger(SubjectExportJob.class);
    private static final String LOCK_NAME = "dsar-export-assembly";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(15);

    private final SchedulingLock schedulingLock;
    private final DsarRequestRepository requestRepository;
    private final DsarRequestServiceImpl requestService;
    private final Map<String, List<SubjectDataProvider>> providersBySubjectType;
    private final List<SubjectDataProvider> allProviders;
    private final ExportStoragePort storage;
    private final Clock clock;

    public SubjectExportJob(SchedulingLock schedulingLock,
                            DsarRequestRepository requestRepository,
                            DsarRequestServiceImpl requestService,
                            List<SubjectDataProvider> providers,
                            ExportStoragePort storage,
                            Clock clock) {
        this.schedulingLock = schedulingLock;
        this.requestRepository = requestRepository;
        this.requestService = requestService;
        this.allProviders = providers;
        this.storage = storage;
        this.clock = clock;

        // Index providers by the subject types they support
        this.providersBySubjectType = new HashMap<>();
        for (SubjectDataProvider p : providers) {
            for (String subjectType : p.supportedSubjectTypes()) {
                this.providersBySubjectType.computeIfAbsent(subjectType, k -> new ArrayList<>()).add(p);
            }
        }
    }

    @Scheduled(fixedDelayString = "${privacy.dsar.export-job-interval-ms:60000}")
    public void runScheduled() {
        schedulingLock.runIfLeader(LOCK_NAME, LEASE_DURATION, this::executeAssembly);
    }

    void executeAssembly() {
        List<DsarRequest> verified = requestRepository.findByState(DsarState.VERIFIED);
        if (verified.isEmpty()) {
            log.debug("dsar.export-job: no VERIFIED requests to process");
            return;
        }
        log.info("dsar.export-job: processing {} VERIFIED request(s)", verified.size());

        for (DsarRequest req : verified) {
            try {
                assembleExport(req);
            } catch (Exception ex) {
                log.error("dsar.export-job: failed for requestId={}", req.getId(), ex);
                // request stays IN_PROGRESS for retry on next cycle
            }
        }
    }

    @Transactional
    void assembleExport(DsarRequest req) {
        // Idempotency: transition to IN_PROGRESS (increments attemptCount)
        requestService.transitionToInProgress(req);

        SubjectRef ref = new SubjectRef(req.getSubjectType(), req.getSubjectId());
        List<SubjectDataSection> sections = collectAllSections(ref);

        String manifestJson = buildManifestJson(sections);
        String exportJson = buildExportJson(req, sections, manifestJson);
        byte[] exportBytes = exportJson.getBytes(StandardCharsets.UTF_8);

        String storageKey = "dsar/" + req.getId() + "/attempt-" + req.getAttemptCount() + ".json";
        storage.store(storageKey, exportBytes);

        Instant now = Instant.now(clock);
        DsarExportArtifact artifact = new DsarExportArtifact(
                req.getId(), storageKey, manifestJson, exportJson,
                (long) exportBytes.length, now);

        requestService.saveArtifactAndFulfill(req, artifact);
        log.info("dsar.export-job: assembled requestId={} storageKey={} sections={}",
                req.getId(), storageKey, sections.size());
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private List<SubjectDataSection> collectAllSections(SubjectRef ref) {
        return allProviders.stream()
                .map(provider -> {
                    if (!provider.supportedSubjectTypes().contains(ref.subjectType())) {
                        return SubjectDataSection.empty(provider.sectionName(), provider.sourceModule());
                    }
                    try {
                        SubjectDataSection section = provider.collect(ref);
                        return section != null ? section
                                : SubjectDataSection.empty(provider.sectionName(), provider.sourceModule());
                    } catch (Exception ex) {
                        log.error("dsar.export-job: provider {} failed for subjectType={} subjectId={}",
                                provider.sectionName(), ref.subjectType(), ref.subjectId(), ex);
                        // AC-6: record erased/unavailable sections rather than fail entire export
                        return new SubjectDataSection(
                                provider.sectionName(), provider.sourceModule(), 0,
                                List.of(Map.of("_error", "DATA_COLLECTION_FAILED",
                                        "_note", ex.getMessage() != null ? ex.getMessage() : "unknown error")));
                    }
                })
                .toList();
    }

    private String buildManifestJson(List<SubjectDataSection> sections) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> entries = sections.stream()
                    .map(s -> {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("sectionName", s.sectionName());
                        entry.put("sourceModule", s.sourceModule());
                        entry.put("rowCount", s.rowCount());
                        return entry;
                    })
                    .toList();
            return mapper.writeValueAsString(entries);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String buildExportJson(DsarRequest req, List<SubjectDataSection> sections,
                                   String manifestJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            Map<String, Object> export = new HashMap<>();
            export.put("dsarRequestId", req.getId().toString());
            export.put("requestType", req.getRequestType());
            export.put("subjectType", req.getSubjectType());
            export.put("generatedAt", Instant.now(clock).toString());
            export.put("attemptCount", req.getAttemptCount());
            export.put("sections", sections.stream()
                    .map(s -> {
                        Map<String, Object> section = new HashMap<>();
                        section.put("sectionName", s.sectionName());
                        section.put("sourceModule", s.sourceModule());
                        section.put("rowCount", s.rowCount());
                        section.put("rows", s.rows());
                        return section;
                    }).toList());
            return mapper.writeValueAsString(export);
        } catch (Exception e) {
            log.error("dsar.export-job: failed to serialize export JSON", e);
            return "{}";
        }
    }
}
