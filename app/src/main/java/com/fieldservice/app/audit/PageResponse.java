package com.fieldservice.app.audit;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Minimal pagination envelope returned by audit endpoints.
 * Refactor to the shared envelope from WO-007 once that story is merged.
 */
public record PageResponse<T>(
    List<T> content,
    long totalElements,
    int totalPages,
    int number,
    int size,
    boolean first,
    boolean last
) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.getNumber(),
            page.getSize(),
            page.isFirst(),
            page.isLast()
        );
    }
}
