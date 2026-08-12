package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link HaversineEstimator}.
 */
class HaversineEstimatorTest {

    private final HaversineEstimator estimator = new HaversineEstimator(30.0);

    @Test
    void distanceKm_knownCoordinates_london_to_manchester() {
        // London (~51.5074, -0.1278) to Manchester (~53.4808, -2.2426)
        // Approximate straight-line distance: ~262 km
        double dist = HaversineEstimator.distanceKm(51.5074, -0.1278, 53.4808, -2.2426);
        assertThat(dist).isCloseTo(262.0, within(5.0));
    }

    @Test
    void estimateMinutes_sameLocation_returnsZero() {
        TravelCoordinate coord = new TravelCoordinate(51.5, -0.1);
        double minutes = estimator.estimateMinutes(coord, coord);
        assertThat(minutes).isCloseTo(0.0, within(0.001));
    }

    @Test
    void estimateMinutes_shortDistance_returnsPositive() {
        TravelCoordinate origin = new TravelCoordinate(51.500, -0.100);
        TravelCoordinate dest = new TravelCoordinate(51.510, -0.100);
        // ~1.1 km at 30 km/h ≈ 2.2 minutes
        double minutes = estimator.estimateMinutes(origin, dest);
        assertThat(minutes).isPositive();
        assertThat(minutes).isLessThan(10.0);
    }

    @Test
    void estimateMinutes_londonToManchester_reasonableEstimate() {
        TravelCoordinate london = new TravelCoordinate(51.5074, -0.1278);
        TravelCoordinate manchester = new TravelCoordinate(53.4808, -2.2426);
        // 262 km / 30 km/h = 524 minutes
        double minutes = estimator.estimateMinutes(london, manchester);
        assertThat(minutes).isCloseTo(524.0, within(20.0));
    }

    @Test
    void constructor_rejectsNonPositiveSpeed() {
        assertThatThrownBy(() -> new HaversineEstimator(0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avgSpeedKmh");

        assertThatThrownBy(() -> new HaversineEstimator(-10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void estimateMinutes_antipodal_doesNotOverflow() {
        TravelCoordinate north = new TravelCoordinate(89.0, 0.0);
        TravelCoordinate south = new TravelCoordinate(-89.0, 180.0);
        double minutes = estimator.estimateMinutes(north, south);
        assertThat(minutes).isPositive();
        assertThat(Double.isFinite(minutes)).isTrue();
    }
}
