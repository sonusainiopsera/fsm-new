package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CoordinateRounder}.
 *
 * <p>Verifies rounding precision (3 decimal places ≈ 111 m) and that
 * nearby coordinates collapse to the same cache key (BR-23 data minimisation).
 */
class CoordinateRounderTest {

    @Test
    void round_threeDecimalPlaces() {
        double rounded = CoordinateRounder.round(51.12345678);
        assertThat(rounded).isEqualTo(51.123);
    }

    @Test
    void round_halfUp() {
        double rounded = CoordinateRounder.round(51.1235);
        assertThat(rounded).isEqualTo(51.124);
    }

    @Test
    void round_coordinate_preservesSign() {
        TravelCoordinate result = CoordinateRounder.round(new TravelCoordinate(-33.86789, 151.20987));
        assertThat(result.latitude()).isEqualTo(-33.868);
        assertThat(result.longitude()).isEqualTo(151.210);
    }

    @Test
    void hash_nearbyCoordinates_sameHash() {
        // Two coordinates that differ by less than 0.001 degree should map to the same hash
        TravelCoordinate a = new TravelCoordinate(51.5001, -0.1001);
        TravelCoordinate b = new TravelCoordinate(51.5009, -0.1009);
        assertThat(CoordinateRounder.hash(a)).isEqualTo(CoordinateRounder.hash(b));
    }

    @Test
    void hash_distantCoordinates_differentHash() {
        TravelCoordinate a = new TravelCoordinate(51.500, -0.100);
        TravelCoordinate b = new TravelCoordinate(51.600, -0.200);
        assertThat(CoordinateRounder.hash(a)).isNotEqualTo(CoordinateRounder.hash(b));
    }

    @Test
    void cacheKey_format() {
        TravelCoordinate origin = new TravelCoordinate(51.500, -0.100);
        TravelCoordinate dest = new TravelCoordinate(51.600, -0.200);
        String key = CoordinateRounder.cacheKey(origin, dest);
        assertThat(key).startsWith("travel:");
        assertThat(key).matches("travel:[0-9a-f]{16}:[0-9a-f]{16}");
    }

    @Test
    void cacheKey_sameCoordinates_symmetricComponents() {
        // origin and destination hashed separately — same coord yields same hash in each position
        TravelCoordinate coord = new TravelCoordinate(51.500, -0.100);
        String key1 = CoordinateRounder.cacheKey(coord, coord);
        // key pattern travel:{same}:{same}
        String[] parts = key1.split(":");
        assertThat(parts).hasSize(3);
        assertThat(parts[1]).isEqualTo(parts[2]);
    }
}
