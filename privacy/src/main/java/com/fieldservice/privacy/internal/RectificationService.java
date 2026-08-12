package com.fieldservice.privacy.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.FieldCorrection;
import com.fieldservice.privacy.api.FieldRectificationResult;
import com.fieldservice.privacy.api.SubjectDataRectifier;
import com.fieldservice.privacy.api.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates allow-listed field-level corrections through per-module
 * {@link SubjectDataRectifier} implementations.
 *
 * <p>Both operations require a DSAR request in state VERIFIED.  The privacy module
 * orchestrates but never writes to another module's tables directly.
 */
@Service
@Transactional(readOnly = true)
class RectificationService {

    private static final Logger log = LoggerFactory.getLogger(RectificationService.class);
    private static final String AGGREGATE_TYPE = "DSAR_REQUEST";

    private static final Set<ClassificationTier> RECTIFIABLE_TIERS =
            EnumSet.of(ClassificationTier.CONFIDENTIAL, ClassificationTier.RESTRICTED);

    private final DsarRequestRepository           requestRepository;
    private final ClassificationRegistry          classificationRegistry;
    private final DomainEventPublisher            eventPublisher;
    private final Map<String, SubjectDataRectifier> rectifiersBySection;
    private final Clock                           clock;
    private final ObjectMapper                    objectMapper;

    RectificationService(DsarRequestRepository requestRepository,
                         ClassificationRegistry classificationRegistry,
                         DomainEventPublisher eventPublisher,
                         List<SubjectDataRectifier> rectifiers,
                         Clock clock,
                         ObjectMapper objectMapper) {
        this.requestRepository      = requestRepository;
        this.classificationRegistry = classificationRegistry;
        this.eventPublisher         = eventPublisher;
        this.clock                  = clock;
        this.objectMapper           = objectMapper;
        this.rectifiersBySection    = rectifiers.stream()
                .collect(Collectors.toMap(SubjectDataRectifier::sectionName, Function.identity()));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    RectificationOutcome rectify(UUID dsarRequestId,
                                  List<FieldCorrection> corrections,
                                  String note,
                                  String actor) {

        DsarRequestEntity dsarRequest = requestRepository.findById(dsarRequestId)
                .orElseThrow(() -> new NotFoundException("DsarRequest", dsarRequestId.toString()));

        if (dsarRequest.getState() != DsarState.VERIFIED) {
            throw new BusinessGuardException("DSAR_NOT_VERIFIED",
                    "DSAR request " + dsarRequestId + " must be in state VERIFIED to authorise rectification"
                    + " (current state: " + dsarRequest.getState() + ")");
        }

        SubjectRef subjectRef = new SubjectRef(
                dsarRequest.getSubjectType(), dsarRequest.getSubjectId());

        validateCorrections(corrections);

        List<FieldRectificationResult> applied = new ArrayList<>();
        List<FieldRectificationResult> skipped = new ArrayList<>();

        for (SubjectDataRectifier rectifier : rectifiersBySection.values()) {
            if (!rectifier.supportedSubjectTypes().contains(subjectRef.subjectType())) {
                continue;
            }
            List<FieldCorrection> relevantCorrections = corrections.stream()
                    .filter(c -> rectifier.supportedEntityNames().contains(c.entityName()))
                    .toList();
            if (relevantCorrections.isEmpty()) {
                continue;
            }
            List<FieldRectificationResult> results = rectifier.rectify(subjectRef, relevantCorrections);
            for (FieldRectificationResult r : results) {
                if (r.applied()) {
                    applied.add(r);
                } else {
                    skipped.add(r);
                }
            }
        }

        corrections.stream()
                .filter(c -> applied.stream().noneMatch(a -> a.entityName().equals(c.entityName()) && a.fieldName().equals(c.fieldName()))
                          && skipped.stream().noneMatch(s -> s.entityName().equals(c.entityName()) && s.fieldName().equals(c.fieldName())))
                .forEach(c -> skipped.add(FieldRectificationResult.skipped(
                        c.entityName(), c.fieldName(), "NO_RECTIFIER_FOUND")));

        publishRectificationEvent(dsarRequestId, actor, applied.size(), skipped.size(), note);

        log.info("subject_rectification_applied dsarId={} subjectType={} subjectId={} applied={} skipped={} actor={}",
                dsarRequestId, subjectRef.subjectType(), subjectRef.subjectId(),
                applied.size(), skipped.size(), actor);

        return new RectificationOutcome(applied, skipped);
    }

    private void validateCorrections(List<FieldCorrection> corrections) {
        List<String> violations = new ArrayList<>();
        for (FieldCorrection correction : corrections) {
            Optional<ClassificationView> classView =
                    classificationRegistry.findByEntityAndField(correction.entityName(), correction.fieldName());
            if (classView.isEmpty()) {
                violations.add(correction.entityName() + "." + correction.fieldName()
                        + ": not found in classification registry");
            } else if (!RECTIFIABLE_TIERS.contains(classView.get().tier())) {
                violations.add(correction.entityName() + "." + correction.fieldName()
                        + ": tier " + classView.get().tier() + " is not rectifiable (must be CONFIDENTIAL or RESTRICTED)");
            }
        }
        if (!violations.isEmpty()) {
            throw new BusinessGuardException("FIELD_NOT_RECTIFIABLE",
                    "One or more fields are not rectifiable: " + String.join("; ", violations));
        }
    }

    private void publishRectificationEvent(UUID dsarRequestId, String actor,
                                            int appliedCount, int skippedCount, String note) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("dsarRequestId", dsarRequestId.toString());
            payload.put("appliedCount", appliedCount);
            payload.put("skippedCount", skippedCount);
            if (note != null) payload.put("note", note);
            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(), "SUBJECT_RECTIFICATION_APPLIED", AGGREGATE_TYPE,
                    dsarRequestId, clock.instant(), MDC.get("traceId"), null, payload));
        } catch (Exception e) {
            log.error("rectification_event_publish_failed dsarId={} error={}", dsarRequestId, e.getMessage());
        }
    }

    record RectificationOutcome(List<FieldRectificationResult> applied,
                                 List<FieldRectificationResult> skipped) {}
}
