package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Rounds coordinates to 3 decimal places (~111 m precision) before hashing.
 *
 * <p>Rounding serves two purposes (BR-23 data minimisation):
 * <ol>
 *   <li>Raises cache hit ratio by collapsing nearby coordinates to the same bucket.</li>
 *   <li>Reduces stored precision of technician location data in the Redis cache.</li>
 * </ol>
 *
 * <p>Key format: {@code travel:{originHash}:{destHash}} where each hash is the
 * first 16 hex characters of SHA-256("{lat},{lon}" after rounding).
 */
final class CoordinateRounder {

    private static final int DECIMAL_PLACES = 3;
    private static final int HASH_PREFIX_LENGTH = 16;

    private CoordinateRounder() {}

    static TravelCoordinate round(TravelCoordinate coord) {
        double lat = round(coord.latitude());
        double lon = round(coord.longitude());
        return new TravelCoordinate(lat, lon);
    }

    static double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(DECIMAL_PLACES, RoundingMode.HALF_UP)
                .doubleValue();
    }

    static String hash(TravelCoordinate coord) {
        TravelCoordinate rounded = round(coord);
        String input = rounded.latitude() + "," + rounded.longitude();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, HASH_PREFIX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String cacheKey(TravelCoordinate origin, TravelCoordinate destination) {
        return "travel:" + hash(origin) + ":" + hash(destination);
    }
}
