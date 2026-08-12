package com.fieldservice.dispatch.web.dto;

import java.util.List;

/**
 * Pre-assignment parts warning returned in the recommendations meta and assignment pre-check (AC-5).
 *
 * <p>Code is always {@code PARTS_UNAVAILABLE} when present.
 * The shortfall list details which parts are unavailable network-wide.
 *
 * @param code       warning code — always {@code "PARTS_UNAVAILABLE"}
 * @param shortfalls per-part network-wide shortfall detail
 */
public record PartsWarning(
        String code,
        List<PartsShortfallEntry> shortfalls
) {
    public static final String PARTS_UNAVAILABLE = "PARTS_UNAVAILABLE";

    public PartsWarning {
        shortfalls = shortfalls == null ? List.of() : List.copyOf(shortfalls);
    }
}
