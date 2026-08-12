package com.fieldservice.audit.internal;

import com.fieldservice.audit.api.AuditRevisionQueryService.FieldDiff;
import com.fieldservice.audit.api.AuditRevisionQueryService.RevisionDetail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Computes field-level before/after diffs between consecutive revision snapshots.
 *
 * <p>Only allow-listed fields from {@link AuditEntityAllowList} are included in the diff.
 * Unaudited, transient, or non-allow-listed fields are silently excluded.
 * PII fields are masked via {@link PiiMaskingPolicy} before the diff value is returned.
 *
 * <p>First-revision case: all non-null fields are shown as added (before=null, after=value).
 * Deleted-entity case: all non-null fields are shown as removed (before=value, after=null).
 */
final class RevisionDiffCalculator {

    private RevisionDiffCalculator() {}

    // SQL column names that are infrastructure/metadata — never shown in diffs.
    private static final Set<String> EXCLUDED_COLUMNS = Set.of(
            "rev", "revtype", "version"
    );

    /**
     * Builds a {@link RevisionDetail} by comparing the current revision snapshot with
     * its predecessor.
     *
     * @param entityType  allow-listed entity type
     * @param entityId    entity UUID
     * @param revInfo     map of REVINFO columns for the current revision
     * @param currentRow  column values from the AUD table for the current revision
     * @param previousRow column values from the AUD table for the previous revision (empty = first)
     * @return populated revision detail with masked field diffs
     */
    static RevisionDetail compute(
            String entityType,
            UUID   entityId,
            Map<String, Object> revInfo,
            Map<String, Object> currentRow,
            Map<String, Object> previousRow) {

        int rev       = ((Number) revInfo.get("rev")).intValue();
        long revtstmp = ((Number) revInfo.get("revtstmp")).longValue();
        String actor  = (String)  revInfo.get("actor_user_id");
        String role   = (String)  revInfo.get("actor_role");

        Short revtype = currentRow.containsKey("revtype")
                ? toShort(currentRow.get("revtype")) : null;

        List<FieldDiff> diffs = new ArrayList<>();

        AuditEntityAllowList.EntityMeta meta =
                AuditEntityAllowList.lookup(entityType).orElseThrow();

        for (String field : meta.diffableFields()) {
            if (EXCLUDED_COLUMNS.contains(field)) continue;

            String currentVal  = stringify(currentRow.get(field));
            String previousVal = previousRow.isEmpty() ? null : stringify(previousRow.get(field));

            boolean isPii = PiiMaskingPolicy.isPiiField(entityType, field);

            String displayCurrent  = isPii && currentVal != null
                    ? PiiMaskingPolicy.MASKED_TOKEN : currentVal;
            String displayPrevious = isPii && previousVal != null
                    ? PiiMaskingPolicy.MASKED_TOKEN : previousVal;

            boolean changed = !Objects.equals(currentVal, previousVal);

            // For ADD revisions (no predecessor): show all non-null as added.
            // For DEL revisions: show all as removed (after = null).
            if (revtype != null && revtype == 0 /* ADD */) {
                if (currentVal != null) {
                    diffs.add(new FieldDiff(field, null, displayCurrent, true, isPii));
                }
            } else if (revtype != null && revtype == 2 /* DEL */) {
                if (previousVal != null) {
                    diffs.add(new FieldDiff(field, displayPrevious, null, true, isPii));
                }
            } else {
                // MOD — include all allow-listed fields, mark changed ones.
                diffs.add(new FieldDiff(field, displayPrevious, displayCurrent, changed, isPii));
            }
        }

        return new RevisionDetail(rev, Instant.ofEpochMilli(revtstmp),
                actor, role, entityType, entityId, diffs);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private static String stringify(Object value) {
        if (value == null) return null;
        return value.toString();
    }

    private static Short toShort(Object value) {
        if (value == null) return null;
        if (value instanceof Short s) return s;
        if (value instanceof Number n) return n.shortValue();
        return null;
    }
}
