package com.fieldservice.domain.workorder;

import java.time.Instant;
import java.util.List;

/**
 * Revision metadata and per-field diff for a single audit revision of a work order.
 */
public record RevisionDto(
    long revisionId,
    Instant revisedAt,
    String actorUserId,
    String actorRole,
    String traceId,
    String clientIp,
    String revisionType,
    List<FieldChangeDto> changes
) {}
