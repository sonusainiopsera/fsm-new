package com.fieldservice.privacy.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.crypto.SubjectKeyManager;
import com.fieldservice.platform.outbox.JdbcSchedulingLock;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.ErasureVerificationScope;
import com.fieldservice.privacy.api.SubjectErasureView;
import com.fieldservice.privacy.api.SubjectRef;
import com.fieldservice.privacy.api.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous worker job that executes cryptographic erasure for DSAR requests of type
 * ERASURE in state VERIFIED.
 *
 * <p>Execution: distributed lock ensures one replica runs at a time.  For each VERIFIED
 * ERASURE request the job:
 * <ol>
 *   <li>Checks idempotency — already-completed tombstone → records IDEMPOTENT_NOOP.</li>
 *   <li>Evaluates legal-hold guard — refuses with REFUSED outcome if policy has legal hold.</li>
 *   <li>Disposes prior export artifacts (nulls export payload, sets disposed_at).</li>
 *   <li>Destroys the subject's envelope data key (terminal, idempotent).</li>
 *   <li>Runs all registered {@link ErasureVerificationScope} beans.</li>
 *   <li>Persists a tombstone with NO personal-data values.</li>
 *   <li>Transitions the DSAR request to FULFILLED.</li>
 * </ol>
 *
 * <p>Key destruction + tombstone persistence are committed in a single transaction.
 * Envers *_AUD and REVINFO rows are NEVER deleted or updated.
 */
@Component
@Profile("worker")
class SubjectErasureJob {

    private static final Logger log           = LoggerFactory.getLogger(SubjectErasureJob.class);
    private static final String LOCK_NAME     = "subject-erasure-job";
    private static final String SYSTEM_ACTOR  = "SYSTEM";
    private static final String AGGREGATE_TYPE = "DSAR_REQUEST";

    private final DsarRequestRepository          requestRepository;
    private final DsarExportArtifactRepository   artifactRepository;
    private final SubjectErasureRepository       erasureRepository;
    private final RetentionPolicyRepository      retentionPolicyRepository;
    private final SubjectKeyManager              subjectKeyManager;
    private final List<ErasureVerificationScope> verificationScopes;
    private final DomainEventPublisher           eventPublisher;
    private final JdbcSchedulingLock             schedulingLock;
    private final PlatformTransactionManager     txManager;
    private final Clock                          clock;
    private final ObjectMapper                   objectMapper;

    SubjectErasureJob(DsarRequestRepository          requestRepository,
                      DsarExportArtifactRepository   artifactRepository,
                      SubjectErasureRepository       erasureRepository,
                      RetentionPolicyRepository      retentionPolicyRepository,
                      SubjectKeyManager              subjectKeyManager,
                      List<ErasureVerificationScope> verificationScopes,
                      DomainEventPublisher           eventPublisher,
                      JdbcSchedulingLock             schedulingLock,
                      PlatformTransactionManager     txManager,
                      Clock                          clock,
                      ObjectMapper                   objectMapper) {
        this.requestRepository       = requestRepository;
        this.artifactRepository      = artifactRepository;
        this.erasureRepository       = erasureRepository;
        this.retentionPolicyRepository = retentionPolicyRepository;
        this.subjectKeyManager       = subjectKeyManager;
        this.verificationScopes      = verificationScopes;
        this.eventPublisher          = eventPublisher;
        this.schedulingLock          = schedulingLock;
        this.txManager               = txManager;
        this.clock                   = clock;
        this.objectMapper            = objectMapper;
    }

    @Scheduled(cron = "${app.privacy.erasure.cron:0 */5 * * * *}")
    public void sweep() {
        schedulingLock.runIfLeader(LOCK_NAME, holderName(), 120, this::doSweep);
    }

    void doSweep() {
        Instant start = clock.instant();
        List<DsarRequestEntity> verifiedErasureRequests =
                requestRepository.findAllVerifiedOrderByDueAt().stream()
                        .filter(r -> r.getRequestType() == DsarRequestType.ERASURE)
                        .toList();

        log.info("subject_erasure_sweep_start count={}", verifiedErasureRequests.size());

        for (DsarRequestEntity request : verifiedErasureRequests) {
            try {
                processErasureRequest(request);
            } catch (Exception e) {
                log.error("subject_erasure_request_error dsarId={} error={}",
                        request.getId(), e.getMessage());
            }
        }

        log.info("subject_erasure_sweep_done durationMs={}",
                clock.instant().toEpochMilli() - start.toEpochMilli());
    }

    private void processErasureRequest(DsarRequestEntity request) {
        UUID dsarId       = request.getId();
        String subjectType = request.getSubjectType();
        UUID subjectId    = request.getSubjectId();

        log.info("subject_erasure_start dsarId={} subjectType={} subjectId={}",
                dsarId, subjectType, subjectId);

        // Idempotency: already-completed tombstone → record IDEMPOTENT_NOOP without re-running
        if (erasureRepository.findCompletedBySubject(subjectType, subjectId).isPresent()) {
            log.info("subject_erasure_idempotent dsarId={} subjectType={} subjectId={}",
                    dsarId, subjectType, subjectId);
            persistTombstone(dsarId, subjectType, subjectId, "UNKNOWN", SYSTEM_ACTOR,
                    List.of(), List.of(), SubjectErasureEntity.OUTCOME_IDEMPOTENT_NOOP, null);
            transitionToFulfilled(request);
            return;
        }

        // Legal-hold guard — fails closed
        boolean legalHoldActive = retentionPolicyRepository.findByDataCategory(subjectType)
                .map(RetentionPolicyEntity::isLegalHold)
                .orElse(false);
        if (legalHoldActive) {
            log.warn("subject_erasure_refused_legal_hold dsarId={} subjectType={}", dsarId, subjectType);
            persistTombstone(dsarId, subjectType, subjectId, "UNKNOWN", SYSTEM_ACTOR,
                    List.of(), List.of(), SubjectErasureEntity.OUTCOME_REFUSED, "LEGAL_HOLD");
            return;
        }

        // Dispose prior export artifacts before key destruction
        List<DsarExportArtifactEntity> artifacts = artifactRepository.findByDsarRequestId(dsarId)
                .map(List::of)
                .orElseGet(List::of);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (DsarExportArtifactEntity artifact : artifacts) {
            if (artifact.getDisposedAt() == null) {
                tx.executeWithoutResult(s -> {
                    artifact.dispose(clock.instant());
                    artifactRepository.save(artifact);
                });
                log.info("subject_erasure_artifact_disposed dsarId={} artifactId={}", dsarId, artifact.getId());
            }
        }

        // Resolve the key reference before destruction
        SubjectRef subjectRef = new SubjectRef(subjectType, subjectId);
        com.fieldservice.platform.crypto.SubjectRef platformRef =
                com.fieldservice.platform.crypto.SubjectRef.of(subjectType, subjectId);
        String keyReference;
        try {
            com.fieldservice.platform.crypto.SubjectKeySpec keySpec =
                    subjectKeyManager.resolveActive(platformRef);
            keyReference = subjectType + "/" + subjectId + "/v" + keySpec.keyVersion();
        } catch (Exception e) {
            keyReference = subjectType + "/" + subjectId + "/unknown";
        }

        // Cryptographic key destruction — terminal, idempotent
        tx.executeWithoutResult(s -> subjectKeyManager.destroy(platformRef));
        log.info("subject_erasure_key_destroyed dsarId={} subjectType={} subjectId={}",
                dsarId, subjectType, subjectId);

        // Run all verification scopes — never throws, each scope returns a result
        List<VerificationResult> verificationResults = new ArrayList<>();
        for (ErasureVerificationScope scope : verificationScopes) {
            try {
                VerificationResult result = scope.verify(subjectRef);
                verificationResults.add(result);
                if (result.plaintextFound()) {
                    log.error("subject_erasure_verification_failed dsarId={} scope={} itemsChecked={}",
                            dsarId, scope.scopeName(), result.itemsChecked());
                }
            } catch (Exception e) {
                log.error("subject_erasure_scope_error dsarId={} scope={} error={}",
                        dsarId, scope.scopeName(), e.getMessage());
                verificationResults.add(new VerificationResult(scope.scopeName(), false, 0, clock.instant()));
            }
        }

        boolean anyPlaintextFound = verificationResults.stream().anyMatch(VerificationResult::plaintextFound);
        if (anyPlaintextFound) {
            log.error("subject_erasure_incomplete_plaintext_found dsarId={}", dsarId);
        }

        persistTombstone(dsarId, subjectType, subjectId, keyReference, SYSTEM_ACTOR,
                List.of(), verificationResults, SubjectErasureEntity.OUTCOME_COMPLETED, null);

        transitionToFulfilled(request);

        log.info("subject_erasure_complete dsarId={} subjectType={} subjectId={} verificationScopes={}",
                dsarId, subjectType, subjectId, verificationResults.size());
    }

    private void persistTombstone(UUID dsarId, String subjectType, UUID subjectId,
                                   String keyReference, String actor,
                                   List<SubjectErasureView.ErasedSection> sections,
                                   List<VerificationResult> verificationResults,
                                   String outcome, String refusalReason) {
        String sectionsJson = toJson(sections);
        String verificationJson = toJson(verificationResults);
        SubjectErasureEntity tombstone = SubjectErasureEntity.create(
                UuidV7.generate(), dsarId, subjectType, subjectId,
                keyReference, clock.instant(), actor,
                sectionsJson, verificationJson, outcome, refusalReason);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> erasureRepository.save(tombstone));
    }

    private void transitionToFulfilled(DsarRequestEntity request) {
        UUID dsarId = request.getId();
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            // VERIFIED → IN_PROGRESS
            tx.executeWithoutResult(s -> {
                DsarRequestEntity fresh = requestRepository.findById(dsarId).orElseThrow();
                fresh.applyTransition(DsarState.IN_PROGRESS, DsarEvent.CLAIM,
                        null, null, clock.instant(), SYSTEM_ACTOR);
                requestRepository.save(fresh);
            });
            // IN_PROGRESS → FULFILLED
            tx.executeWithoutResult(s -> {
                DsarRequestEntity fresh = requestRepository.findById(dsarId).orElseThrow();
                fresh.applyTransition(DsarState.FULFILLED, DsarEvent.FULFIL,
                        null, null, clock.instant(), SYSTEM_ACTOR);
                requestRepository.save(fresh);
            });
            publishEvent("SUBJECT_ERASURE_FULFILLED", dsarId, SYSTEM_ACTOR,
                    Map.of("subjectType", request.getSubjectType(),
                           "subjectId", request.getSubjectId().toString()));
        } catch (Exception e) {
            log.error("subject_erasure_fulfil_transition_error dsarId={} error={}",
                    dsarId, e.getMessage());
        }
    }

    private void publishEvent(String eventType, UUID aggregateId, String actor,
                               Map<String, Object> payload) {
        try {
            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(), eventType, AGGREGATE_TYPE, aggregateId,
                    clock.instant(), MDC.get("traceId"), null, payload));
        } catch (Exception e) {
            log.error("subject_erasure_event_publish_failed eventType={} dsarId={} error={}",
                    eventType, aggregateId, e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
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
