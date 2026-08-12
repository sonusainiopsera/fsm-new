package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.DisposalMethod;
import com.fieldservice.privacy.api.RetentionPeriodUnit;
import com.fieldservice.privacy.api.RetentionPolicyView;

import java.time.Instant;
import java.util.UUID;

/**
 * JSON response body for retention policy endpoints.
 */
public record RetentionPolicyResponse(
        UUID               id,
        String             dataCategory,
        String             entityName,
        int                periodValue,
        RetentionPeriodUnit periodUnit,
        String             anchorField,
        DisposalMethod     disposalMethod,
        boolean            legalHold,
        boolean            ratified,
        boolean            enabled,
        String             notes,
        Instant            createdAt,
        String             createdBy,
        Instant            updatedAt,
        String             updatedBy,
        int                version
) {
    static RetentionPolicyResponse from(RetentionPolicyView view) {
        return new RetentionPolicyResponse(
                view.id(), view.dataCategory(), view.entityName(),
                view.periodValue(), view.periodUnit(), view.anchorField(),
                view.disposalMethod(), view.legalHold(), view.ratified(),
                view.enabled(), view.notes(),
                view.createdAt(), view.createdBy(), view.updatedAt(), view.updatedBy(),
                view.version());
    }
}
