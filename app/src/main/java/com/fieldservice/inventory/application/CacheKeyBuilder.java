package com.fieldservice.inventory.application;

import com.fieldservice.inventory.api.PartsAvailabilityQuery;
import com.fieldservice.inventory.api.RequiredPartQuantity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Builds a stable, content-addressable cache key for a {@link PartsAvailabilityQuery}.
 *
 * <p>Keys are deterministic: the same set of parts, vehicle locations, and warehouse
 * locations always produces the same key regardless of input ordering. SHA-256 over
 * a stable canonical string representation.
 */
final class CacheKeyBuilder {

    private CacheKeyBuilder() {}

    /**
     * Returns a stable hex-encoded SHA-256 digest of the query's canonical form.
     */
    static String build(PartsAvailabilityQuery query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical(query).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String canonical(PartsAvailabilityQuery query) {
        StringBuilder sb = new StringBuilder();

        // Parts: sorted by partId, then requiredQuantity
        List<RequiredPartQuantity> parts = new ArrayList<>(query.requiredParts());
        parts.sort(Comparator.comparing((RequiredPartQuantity r) -> r.partId().toString())
                .thenComparingInt(RequiredPartQuantity::requiredQuantity));
        sb.append("parts:");
        for (RequiredPartQuantity r : parts) {
            if (r.requiredQuantity() > 0) {
                sb.append(r.partId()).append('=').append(r.requiredQuantity()).append(';');
            }
        }

        // Vehicle locations: sorted
        sb.append("|vehicles:");
        query.candidateVehicleLocationIds().stream()
                .map(UUID::toString).sorted()
                .forEach(id -> sb.append(id).append(';'));

        // Warehouse locations: sorted
        sb.append("|warehouses:");
        query.reachableWarehouseLocationIds().stream()
                .map(UUID::toString).sorted()
                .forEach(id -> sb.append(id).append(';'));

        return sb.toString();
    }
}
