package com.fieldservice.privacy.internal;

import com.fieldservice.platform.crypto.SubjectKeyManager;
import com.fieldservice.platform.crypto.SubjectKeyState;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ErasureView;
import com.fieldservice.privacy.api.RectifyRequest;
import com.fieldservice.privacy.api.RectifyResponse;
import com.fieldservice.privacy.api.SubjectDataRectifier;
import com.fieldservice.privacy.api.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates per-module field-level corrections for a data subject.
 *
 * <p>The allow-list is resolved from the classification registry: only fields
 * classified as {@link ClassificationTier#CONFIDENTIAL} or
 * {@link ClassificationTier#RESTRICTED} may be corrected. Any request for an
 * unclassified or INTERNAL/PUBLIC field is rejected with 400.
 *
 * <p>Corrections are applied inside a single transaction via the per-module
 * {@link SubjectDataRectifier} implementations. The privacy module never updates
 * another module's tables directly.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
class RectificationService {

    private static final Logger log = LoggerFactory.getLogger(RectificationService.class);

    private static final String CONFIRMATION_TOKEN = "CONFIRM_ERASURE";

    private final DsarRequestRepository dsarRequestRepository;
    private final ClassificationRegistry classificationRegistry;
    private final SubjectKeyManager keyManager;
    private final Map<String, SubjectDataRectifier> rectifiersByModule;

    RectificationService(DsarRequestRepository dsarRequestRepository,
                         ClassificationRegistry classificationRegistry,
                         SubjectKeyManager keyManager,
                         List<SubjectDataRectifier> rectifiers) {
        this.dsarRequestRepository = dsarRequestRepository;
        this.classificationRegistry = classificationRegistry;
        this.keyManager = keyManager;
        this.rectifiersByModule = rectifiers.stream()
                .collect(Collectors.toMap(SubjectDataRectifier::module, Function.identity()));
    }

    /**
     * Applies allow-listed field corrections for the given subject.
     */
    RectifyResponse rectify(String subjectType, UUID subjectId, RectifyRequest req) {
        // Guard 1: authorising DSAR request must exist and be VERIFIED or later
        DsarRequest dsar = dsarRequestRepository.findById(req.dsarRequestId())
                .orElseThrow(() -> new NotFoundException("DsarRequest", req.dsarRequestId()));
        assertVerifiedOrLater(dsar, "rectification");

        // Guard 2: data key must not be destroyed
        SubjectKeyState keyState = keyManager.getState(subjectType, subjectId);
        if (keyState == SubjectKeyState.DESTROYED) {
            throw new BusinessGuardException("KEY_DESTROYED",
                    "Subject's data key is destroyed — rectification is no longer possible");
        }

        SubjectRef ref = new SubjectRef(subjectType, subjectId);

        var applied  = new ArrayList<RectifyResponse.AppliedCorrection>();
        var skipped  = new ArrayList<RectifyResponse.SkippedCorrection>();

        for (RectifyRequest.FieldCorrection correction : req.corrections()) {
            // Guard 3: field must be on the allow-list (CONFIDENTIAL or RESTRICTED)
            var classification = classificationRegistry.findByEntityAndField(
                    correction.entityName(), correction.fieldName());
            if (classification.isEmpty()) {
                skipped.add(new RectifyResponse.SkippedCorrection(
                        correction.entityName(), correction.fieldName(),
                        "NOT_CLASSIFIED — field not in the data classification registry"));
                continue;
            }
            ClassificationTier tier = classification.get().tier();
            if (tier != ClassificationTier.CONFIDENTIAL && tier != ClassificationTier.RESTRICTED) {
                skipped.add(new RectifyResponse.SkippedCorrection(
                        correction.entityName(), correction.fieldName(),
                        "NOT_RECTIFIABLE — only CONFIDENTIAL and RESTRICTED fields may be corrected, tier=" + tier));
                continue;
            }

            // Route to the owning module's rectifier
            boolean routed = false;
            for (SubjectDataRectifier rectifier : rectifiersByModule.values()) {
                if (!rectifier.supportedSubjectTypes().contains(subjectType)) continue;

                var result = rectifier.rectify(ref,
                        correction.entityName(), correction.fieldName(), correction.newValue());

                switch (result) {
                    case SubjectDataRectifier.RectifyFieldResult.Applied a -> {
                        applied.add(new RectifyResponse.AppliedCorrection(
                                a.entityName(), a.fieldName(), a.revisionId()));
                        routed = true;
                    }
                    case SubjectDataRectifier.RectifyFieldResult.Refused r -> {
                        skipped.add(new RectifyResponse.SkippedCorrection(
                                r.entityName(), r.fieldName(), "REFUSED — " + r.reason()));
                        routed = true;
                    }
                    case SubjectDataRectifier.RectifyFieldResult.Skipped s -> {
                        // Not this module's entity — try next
                    }
                }
                if (routed) break;
            }
            if (!routed) {
                skipped.add(new RectifyResponse.SkippedCorrection(
                        correction.entityName(), correction.fieldName(),
                        "NO_MODULE — no rectifier handles entity=" + correction.entityName()));
            }
        }

        log.info("Rectification complete: subjectType={} applied={} skipped={} dsarId={}",
                subjectType, applied.size(), skipped.size(), req.dsarRequestId());

        return new RectifyResponse(List.copyOf(applied), List.copyOf(skipped));
    }

    private static void assertVerifiedOrLater(DsarRequest dsar, String operation) {
        DsarState state = dsar.getState();
        if (state == DsarState.RECEIVED || state == DsarState.IDENTITY_PENDING) {
            throw new BusinessGuardException("UNVERIFIED_REQUEST",
                    "DSAR request " + dsar.getId() + " is not yet VERIFIED — " + operation + " requires VERIFIED or later state (current: " + state + ")");
        }
        if (state == DsarState.REJECTED || state == DsarState.WITHDRAWN) {
            throw new BusinessGuardException("TERMINAL_REQUEST",
                    "DSAR request " + dsar.getId() + " is in terminal state " + state + " — " + operation + " cannot proceed");
        }
    }
}
