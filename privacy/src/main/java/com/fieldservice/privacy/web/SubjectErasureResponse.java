package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.SubjectErasureView;
import com.fieldservice.privacy.api.VerificationResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubjectErasureResponse(
        UUID id,
        UUID dsarRequestId,
        String subjectType,
        UUID subjectId,
        Instant erasedAt,
        String actor,
        List<SectionDto> sections,
        List<VerificationDto> verification,
        String outcome,
        String refusalReason) {

    public record SectionDto(String name, int rowCount) {}

    public record VerificationDto(String scope, boolean plaintextFound, Instant checkedAt) {}

    static SubjectErasureResponse from(SubjectErasureView view) {
        List<SectionDto> sections = view.erasedSections().stream()
                .map(s -> new SectionDto(s.name(), s.rowCount()))
                .toList();
        List<VerificationDto> verification = view.verificationResults().stream()
                .map(v -> new VerificationDto(v.scope(), v.plaintextFound(), v.checkedAt()))
                .toList();
        return new SubjectErasureResponse(
                view.id(), view.dsarRequestId(), view.subjectType(), view.subjectId(),
                view.erasedAt(), view.actor(), sections, verification,
                view.outcome(), view.refusalReason());
    }
}
