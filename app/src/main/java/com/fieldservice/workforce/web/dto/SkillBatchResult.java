package com.fieldservice.workforce.web.dto;

import java.util.List;

/** Per-row result list for a batch skill-upsert. */
public record SkillBatchResult(List<RowResult> results) {

    public record RowResult(int row, String skillCode, String status, List<String> errors) {

        public static RowResult ok(int row, String skillCode) {
            return new RowResult(row, skillCode, "OK", List.of());
        }

        public static RowResult error(int row, String skillCode, List<String> errors) {
            return new RowResult(row, skillCode, "ERROR", errors);
        }
    }
}
