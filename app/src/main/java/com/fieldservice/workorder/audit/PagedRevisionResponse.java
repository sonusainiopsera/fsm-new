package com.fieldservice.workorder.audit;

import java.util.List;

/**
 * Simple pagination envelope for revision queries.
 * Mirrors the structure that WO-007 will formalise; refactored once that envelope is merged.
 */
public record PagedRevisionResponse(
        List<RevisionEntry> content,
        int    page,
        int    size,
        long   totalElements,
        int    totalPages
) {
    static PagedRevisionResponse of(List<RevisionEntry> content, int page, int size, long total) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PagedRevisionResponse(content, page, size, total, totalPages);
    }
}
