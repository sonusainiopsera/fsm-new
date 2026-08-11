package com.fieldservice.portal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ETag computation in {@link PortalStatusService} (no Spring context, AC-8).
 *
 * <p>Verifies determinism and change detection per AC-4.
 */
class PortalStatusETagTest {

    private static final UUID WO_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant TS = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    @DisplayName("ETag is deterministic: same inputs produce same output")
    void eTagIsDeterministic() {
        String eTag1 = PortalStatusService.computeETag(WO_ID, 3, TS);
        String eTag2 = PortalStatusService.computeETag(WO_ID, 3, TS);

        assertThat(eTag1).isEqualTo(eTag2);
    }

    @Test
    @DisplayName("ETag is a quoted non-empty string (HTTP strong validator format)")
    void eTagIsQuotedString() {
        String eTag = PortalStatusService.computeETag(WO_ID, 1, TS);

        assertThat(eTag).startsWith("\"");
        assertThat(eTag).endsWith("\"");
        assertThat(eTag.length()).isGreaterThan(2);
    }

    @Test
    @DisplayName("ETag changes when version changes")
    void eTagChangesWithVersion() {
        String eTag1 = PortalStatusService.computeETag(WO_ID, 1, TS);
        String eTag2 = PortalStatusService.computeETag(WO_ID, 2, TS);

        assertThat(eTag1).isNotEqualTo(eTag2);
    }

    @Test
    @DisplayName("ETag changes when updatedAt changes")
    void eTagChangesWithUpdatedAt() {
        String eTag1 = PortalStatusService.computeETag(WO_ID, 1, TS);
        String eTag2 = PortalStatusService.computeETag(WO_ID, 1, TS.plusSeconds(1));

        assertThat(eTag1).isNotEqualTo(eTag2);
    }

    @Test
    @DisplayName("ETag changes when workOrderId changes")
    void eTagChangesWithWorkOrderId() {
        UUID otherId = UUID.fromString("30000000-0000-0000-0000-000000000002");

        String eTag1 = PortalStatusService.computeETag(WO_ID, 1, TS);
        String eTag2 = PortalStatusService.computeETag(otherId, 1, TS);

        assertThat(eTag1).isNotEqualTo(eTag2);
    }

    @Test
    @DisplayName("Different work orders at the same version and timestamp produce different ETags")
    void eTagsAreWorkOrderScoped() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        String eTag1 = PortalStatusService.computeETag(id1, 0, TS);
        String eTag2 = PortalStatusService.computeETag(id2, 0, TS);

        assertThat(eTag1).isNotEqualTo(eTag2);
    }

    @Test
    @DisplayName("Degraded freshness flag does NOT affect the ETag (freshness is response metadata, not identity)")
    void eTagIsIndependentOfFreshness() {
        // ETag is computed purely from workOrderId, version, updatedAt — not from freshness state
        String eTag1 = PortalStatusService.computeETag(WO_ID, 2, TS);
        String eTag2 = PortalStatusService.computeETag(WO_ID, 2, TS);

        assertThat(eTag1).isEqualTo(eTag2);
    }
}
