package com.fieldservice.geo;

import com.fieldservice.geo.api.Coordinates;
import com.fieldservice.geo.internal.HaversineEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Unit tests for {@link HaversineEstimator} — pure math, no network.
 */
class HaversineEstimatorTest {

    private final HaversineEstimator estimator = new HaversineEstimator(50.0);

    @Test
    @DisplayName("London to Oxford: ~96 km → ~115 min at 50 kph")
    void londonToOxford() {
        // Real great-circle distance: ~95.7 km
        double km = HaversineEstimator.distanceKm(51.5074, -0.1278, 51.7520, -1.2577);
        assertThat(km).isCloseTo(95.7, offset(1.0));

        int minutes = estimator.estimateMinutes(
                new Coordinates(51.5074, -0.1278),
                new Coordinates(51.7520, -1.2577));
        // 95.7 km / 50 kph * 60 ≈ 115 min
        assertThat(minutes).isBetween(110, 120);
    }

    @Test
    @DisplayName("same origin and destination: returns 0 minutes")
    void samePoint_zeroMinutes() {
        Coordinates p = new Coordinates(51.5074, -0.1278);
        assertThat(estimator.estimateMinutes(p, p)).isEqualTo(0);
    }

    @Test
    @DisplayName("antipodal points: returns large but finite minutes")
    void antipodalPoints() {
        // Antipodal: ~20000 km → ~24000 min
        int minutes = estimator.estimateMinutes(
                new Coordinates(0.0, 0.0),
                new Coordinates(0.0, 180.0));
        assertThat(minutes).isBetween(20000, 30000);
    }

    @Test
    @DisplayName("invalid speed factor (zero) throws at construction")
    void invalidSpeed_throws() {
        assertThatThrownBy(() -> new HaversineEstimator(0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("averageSpeedKph");
    }

    @Test
    @DisplayName("north-south pole to equator: distance ~10000 km")
    void poleToEquator() {
        double km = HaversineEstimator.distanceKm(90.0, 0.0, 0.0, 0.0);
        assertThat(km).isCloseTo(10007.5, offset(10.0));
    }
}
