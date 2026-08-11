package com.fieldservice.privacy.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.crypto.SubjectKeyManager;
import com.fieldservice.platform.crypto.SubjectKeyState;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.ErasureVerificationScope;
import com.fieldservice.privacy.api.ErasureView;
import com.fieldservice.privacy.api.InitiateErasureRequest;
import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates cryptographic erasure: guards → key destruction → verification → tombstone.
 *
 * <p><strong>Erasure algorithm:</strong>
 * <ol>
 *   <li>Load and validate the authorising DSAR request (VERIFIED or later).</li>
 *   <li>Idempotency: if a COMPLETED tombstone already exists, return it as IDEMPOTENT_NOOP.</li>
 *   <li>Apply legal-hold guard (refuses if any retention policy is in legal hold for the subject).</li>
 *   <li>Evict subject entries from Redis and dispose prior export artifacts.</li>
 *   <li>Call {@link SubjectKeyManager#destroy} — this is the erasure; it is idempotent.</li>
 *   <li>Run all registered {@link ErasureVerificationScope} beans and collect results.</li>
 *   <li>Persist the append-only {@link SubjectErasure} tombstone with no PII values.</li>
 * </ol>
 *
 * <p>Key destruction failures abort the transaction with outcome REFUSED — no partial erasure is recorded.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
class SubjectErasureJob {

    private static final Logger log = LoggerFactory.getLogger(SubjectErasureJob.class);
    private static final String CONFIRMATION_TOKEN = "CONFIRM_ERASURE";

    private final DsarRequestRepository dsarRequestRepository;
    private final SubjectErasureRepository erasureRepository;
    private final DsarExportArtifactRepository artifactRepository;
    private final SubjectKeyManager keyManager;
    private final List<ErasureVerificationScope> verificationScopes;
    private final List<SubjectDataProvider> dataProviders;
    private final RetentionPolicyRepository retentionPolicyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    SubjectErasureJob(DsarRequestRepository dsarRequestRepository,
                      SubjectErasureRepository erasureRepository,
                      DsarExportArtifactRepository artifactRepository,
                      SubjectKeyManager keyManager,
                      List<ErasureVerificationScope> verificationScopes,
                      List<SubjectDataProvider> dataProviders,
                      RetentionPolicyRepository retentionPolicyRepository,
                      ObjectMapper objectMapper,
                      Clock clock) {
        this.dsarRequestRepository  = dsarRequestRepository;
        this.erasureRepository      = erasureRepository;
        this.artifactRepository     = artifactRepository;
        this.keyManager             = keyManager;
        this.verificationScopes     = verificationScopes;
        this.dataProviders          = dataProviders;
        this.retentionPolicyRepository = retentionPolicyRepository;
        this.objectMapper           = objectMapper;
        this.clock                  = clock;
    }

    /**
     * Initiates erasure for the given subject. Returns immediately with a tombstone view;
     * the actual key destruction is synchronous within this call.
     */
    ErasureView initiateErasure(String subjectType, UUID subjectId, InitiateErasureRequest req) {
        // Guard 1: confirmation token
        if (!CONFIRMATION_TOKEN.equals(req.confirmation())) {
            throw new BusinessGuardException("CONFIRMATION_MISSING",
                    "Erasure confirmation token must be 'CONFIRM_ERASURE'");
        }

        // Guard 2: authorising DSAR request must be VERIFIED or later
        DsarRequest dsar = dsarRequestRepository.findById(req.dsarRequestId())
                .orElseThrow(() -> new NotFoundException("DsarRequest", req.dsarRequestId()));
        assertVerifiedOrLater(dsar);

        // Guard 3: subject type on DSAR must match
        if (!subjectType.equals(dsar.getSubjectType()) || !subjectId.equals(dsar.getSubjectId())) {
            throw new BusinessGuardException("SUBJECT_MISMATCH",
                    "DSAR request subject does not match the erasure subject");
        }

        SubjectRef ref = new SubjectRef(subjectType, subjectId);

        // Idempotency: already completed → return as IDEMPOTENT_NOOP
        Optional<SubjectErasure> existing = erasureRepository
                .findBySubjectTypeAndSubjectIdAndOutcome(subjectType, subjectId, "COMPLETED");
        if (existing.isPresent()) {
            log.info("Idempotent erasure: subject already erased. subjectType={} subjectId={}",
                    subjectType, subjectId);
            SubjectErasure tombstone = existing.get();
            return buildView(tombstone);
        }

        // Guard 4: legal hold check
        assertNoLegalHold(subjectType);

        String actor = resolveActor();

        // Collect section row counts before erasure (counts are non-PII)
        List<ErasureView.ErasedSection> sections = collectSectionCounts(ref);

        // Dispose prior export artifacts
        disposeExportArtifacts(req.dsarRequestId());

        // Evict key cache before destruction
        keyManager.evictCache(subjectType, subjectId);

        // KEY DESTRUCTION — this is the erasure
        int keyVersion = keyManager.currentVersion(subjectType, subjectId);
        String keyReference = subjectType + ":" + subjectId + ":v" + keyVersion;
        try {
            keyManager.destroy(subjectType, subjectId);
        } catch (Exception ex) {
            log.error("Key destruction failed for subjectType={} — erasure aborted. traceId={}",
                    subjectType, org.slf4j.MDC.get("traceId"));
            throw new BusinessGuardException("KEY_DESTRUCTION_FAILED",
                    "Key destruction failed — erasure aborted. No data was modified.");
        }

        // Post-erasure verification
        Instant now = Instant.now(clock);
        List<ErasureView.ScopeVerification> verifications = runVerification(ref, now);
        boolean anyPlaintextFound = verifications.stream()
                .anyMatch(ErasureView.ScopeVerification::plaintextFound);
        if (anyPlaintextFound) {
            log.warn("Verification WARNING: plaintext found after erasure for subjectType={}. Scopes={}",
                    subjectType,
                    verifications.stream().filter(ErasureView.ScopeVerification::plaintextFound)
                            .map(ErasureView.ScopeVerification::scopeId).toList());
        }

        // Persist tombstone
        SubjectErasure tombstone = new SubjectErasure(
                dsar.getId(),
                subjectType, subjectId,
                keyReference,
                now, actor,
                serialize(sections),
                serialize(verifications),
                "COMPLETED",
                null);
        erasureRepository.save(tombstone);

        log.info("Erasure COMPLETED: subjectType={} sections={} verificationScopes={} plaintextFound={}",
                subjectType, sections.size(), verifications.size(), anyPlaintextFound);

        return new ErasureView(tombstone.getId(), subjectType, subjectId,
                now, actor, sections, verifications, "COMPLETED", null);
    }

    ErasureView getErasure(UUID erasureId) {
        SubjectErasure tombstone = erasureRepository.findById(erasureId)
                .orElseThrow(() -> new NotFoundException("SubjectErasure", erasureId));
        return buildView(tombstone);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void assertVerifiedOrLater(DsarRequest dsar) {
        DsarState state = dsar.getState();
        if (state == DsarState.RECEIVED || state == DsarState.IDENTITY_PENDING) {
            throw new BusinessGuardException("UNVERIFIED_REQUEST",
                    "DSAR request " + dsar.getId() + " must be VERIFIED or later (current: " + state + ")");
        }
        if (state == DsarState.REJECTED || state == DsarState.WITHDRAWN) {
            throw new BusinessGuardException("TERMINAL_REQUEST",
                    "DSAR request " + dsar.getId() + " is in terminal state " + state);
        }
    }

    private void assertNoLegalHold(String subjectType) {
        boolean hasLegalHold = retentionPolicyRepository.findAll().stream()
                .anyMatch(p -> p.isLegalHold() && p.isEnabled() && p.isRatified());
        if (hasLegalHold) {
            throw new BusinessGuardException("LEGAL_HOLD",
                    "An active legal hold exists — erasure is refused until the hold is lifted");
        }
    }

    private List<ErasureView.ErasedSection> collectSectionCounts(SubjectRef ref) {
        var sections = new ArrayList<ErasureView.ErasedSection>();
        for (SubjectDataProvider provider : dataProviders) {
            if (!provider.supportedSubjectTypes().contains(ref.subjectType())) continue;
            try {
                var section = provider.collect(ref);
                sections.add(new ErasureView.ErasedSection(section.sectionName(), section.rowCount()));
            } catch (Exception ex) {
                log.warn("SubjectDataProvider '{}' failed during section count collection: {}",
                        provider.sectionName(), ex.getMessage());
                sections.add(new ErasureView.ErasedSection(provider.sectionName(), -1));
            }
        }
        return sections;
    }

    private void disposeExportArtifacts(UUID dsarRequestId) {
        Instant now = Instant.now(clock);
        artifactRepository.findByDsarRequestId(dsarRequestId).ifPresent(artifact -> {
            if (artifact.getDisposedAt() == null) {
                artifact.dispose(now);
                artifactRepository.save(artifact);
                log.info("Disposed export artifact for dsarRequestId={}", dsarRequestId);
            }
        });
    }

    private List<ErasureView.ScopeVerification> runVerification(SubjectRef ref, Instant now) {
        var results = new ArrayList<ErasureView.ScopeVerification>();
        for (ErasureVerificationScope scope : verificationScopes) {
            try {
                var result = scope.verify(ref, now);
                results.add(new ErasureView.ScopeVerification(
                        result.scopeId(), result.plaintextFound(),
                        result.recordsChecked(), result.checkedAt()));
            } catch (Exception ex) {
                log.warn("Verification scope '{}' threw: {}", scope.scopeId(), ex.getMessage());
                results.add(new ErasureView.ScopeVerification(scope.scopeId(), true, 0, now));
            }
        }
        return results;
    }

    private ErasureView buildView(SubjectErasure tombstone) {
        List<ErasureView.ErasedSection> sections = List.of();
        List<ErasureView.ScopeVerification> verifications = List.of();
        try {
            sections = objectMapper.readValue(tombstone.getErasedSections(),
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ErasureView.ErasedSection.class));
            verifications = objectMapper.readValue(tombstone.getVerificationResult(),
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ErasureView.ScopeVerification.class));
        } catch (JsonProcessingException ex) {
            log.warn("Could not deserialize tombstone JSON for id={}: {}", tombstone.getId(), ex.getMessage());
        }
        return tombstone.toView(sections, verifications);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize tombstone field: {}", ex.getMessage());
            return "[]";
        }
    }

    private static String resolveActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
