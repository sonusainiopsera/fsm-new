package com.fieldservice.workforce.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write access to the last-known technician position cache.
 * Consumed by the dispatch scoring path to prefer real-time position over home-base site.
 */
public interface TechnicianPositionCachePort {

    /**
     * Returns the cached position for a technician, or empty when absent or expired.
     */
    Optional<CachedPosition> findCachedPosition(UUID technicianId);

    /**
     * Writes a position to the cache with the standard TTL.
     * Failures are silently suppressed — the caller proceeds regardless.
     */
    void writeCachedPosition(UUID technicianId, CachedPosition position);

    record CachedPosition(double latitude, double longitude, int accuracyMetres, Instant capturedAt) {}
}
