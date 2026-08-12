package com.fieldservice.dispatch.web.dto;

/**
 * Aggregate count of candidates excluded for a given reason.
 */
public record ExclusionSummaryDto(String reason, long count) {
}
