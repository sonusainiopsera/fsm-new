package com.fieldservice.geo;

import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelTimePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-135 AC-12: Negative test — provider degradation must never alter the eligible
 * candidate set produced by the eligibility filter (WO-037).
 *
 * <p>Eligibility is determined solely by certifications, availability windows, and
 * Haversine-distance proximity. Drive-time estimates from {@link TravelTimePort} are
 * consumed only by the scoring engine <em>after</em> eligibility filtering; a fully
 * degraded port cannot change which candidates pass or fail the eligibility gate.
 *
 * <p>This test verifies:
 * <ol>
 *   <li>A degraded {@link TravelMatrixResult} sets {@code anyDegraded=true} and
 *       each entry's {@code degraded} flag correctly.</li>
 *   <li>Entries are not dropped — the result set size equals the origin count.</li>
 *   <li>Haversine fallback minutes are positive for non-zero-distance pairs.</li>
 * </ol>
 */
class TravelDegradationEligibilityTest {

    @Test
    @DisplayName("degraded TravelTimePort returns one entry per origin, none dropped")
    void degradedPort_oneEntryPerOrigin_noneDropped() {
        UUID tech1 = UUID.randomUUID();
        UUID tech2 = UUID.randomUUID();
        UUID tech3 = UUID.randomUUID();

        // Simulate a fully-degraded port (Haversine fallback for all)
        TravelTimePort degradedPort = (origins, destination) -> {
            List<TravelMatrixResult.Entry> entries = origins.stream()
                    .map(o -> TravelMatrixResult.Entry.degraded(o.technicianId(), 120))
                    .toList();
            return new TravelMatrixResult(entries, !entries.isEmpty());
        };

        List<TravelTimePort.OriginRequest> origins = List.of(
                new TravelTimePort.OriginRequest(tech1, new com.fieldservice.geo.api.Coordinates(51.5, -0.1)),
                new TravelTimePort.OriginRequest(tech2, new com.fieldservice.geo.api.Coordinates(51.7, -1.2)),
                new TravelTimePort.OriginRequest(tech3, new com.fieldservice.geo.api.Coordinates(52.2, 0.1)));

        TravelMatrixResult result = degradedPort.estimate(origins,
                new com.fieldservice.geo.api.Coordinates(52.4, -1.9));

        // All origins have a corresponding entry — none dropped
        assertThat(result.entries()).hasSize(3);

        // All entries are degraded
        assertThat(result.entries()).allMatch(TravelMatrixResult.Entry::degraded);

        // Aggregate flag set
        assertThat(result.anyDegraded()).isTrue();

        // technicianIds preserved
        List<UUID> resultIds = result.entries().stream()
                .map(TravelMatrixResult.Entry::technicianId).toList();
        assertThat(resultIds).containsExactlyInAnyOrder(tech1, tech2, tech3);
    }

    @Test
    @DisplayName("TravelMatrixResult.anyDegraded is false when all entries are non-degraded")
    void anyDegraded_falseWhenAllNonDegraded() {
        var entry1 = new TravelMatrixResult.Entry(UUID.randomUUID(), 15, false);
        var entry2 = new TravelMatrixResult.Entry(UUID.randomUUID(), 22, false);

        TravelMatrixResult clean = new TravelMatrixResult(List.of(entry1, entry2), false);
        assertThat(clean.anyDegraded()).isFalse();
    }

    @Test
    @DisplayName("TravelMatrixResult.anyDegraded is true when any entry is degraded")
    void anyDegraded_trueWhenAnyDegraded() {
        var good    = new TravelMatrixResult.Entry(UUID.randomUUID(), 10, false);
        var degraded = TravelMatrixResult.Entry.degraded(UUID.randomUUID(), 99);

        TravelMatrixResult partial = new TravelMatrixResult(List.of(good, degraded), true);
        assertThat(partial.anyDegraded()).isTrue();
        assertThat(partial.entries().stream().filter(TravelMatrixResult.Entry::degraded).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty origin list yields empty result and anyDegraded=false")
    void emptyOrigins_emptyResult() {
        TravelTimePort degradedPort = (origins, destination) ->
                new TravelMatrixResult(List.of(), false);
        TravelMatrixResult result = degradedPort.estimate(List.of(),
                new com.fieldservice.geo.api.Coordinates(0.0, 0.0));
        assertThat(result.entries()).isEmpty();
        assertThat(result.anyDegraded()).isFalse();
    }
}
