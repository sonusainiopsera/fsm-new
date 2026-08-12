package com.fieldservice.dispatch.web.dto;

import java.util.List;

/**
 * Top-level recommendation response envelope.
 *
 * <p>Mirrors the platform {@code PagedResponse} contract but adds {@code meta} for
 * business context (snapshot id, degradation flags, pool size, etc.) which is not
 * expressible in the generic pagination envelope.
 */
public record RecommendationResponse(
        List<CandidateDto>   data,
        PageInfo             page,
        Links                links,
        RecommendationMeta   meta) {

    /**
     * Keyset page descriptor: only {@code size} and {@code hasNext} are meaningful;
     * total element count is unknown without a separate full-table scan.
     */
    public record PageInfo(int size, boolean hasNext) {}

    /** Navigation links. {@code next} is null on the last page. */
    public record Links(String next) {}
}
