package com.fieldservice.workorder.holds;

import com.fieldservice.domain.workorder.HoldReason;
import com.fieldservice.domain.workorder.HoldReasonRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exposes the active hold reason vocabulary with a short-TTL Redis cache.
 *
 * <p>On cache miss the service reads from the database and repopulates the cache.
 * Redis unavailability falls back silently to the database read and logs at WARN.
 */
@Service
@Transactional(readOnly = true)
public class HoldReasonService {

    private static final Logger log = LoggerFactory.getLogger(HoldReasonService.class);
    private static final String CACHE_KEY = "hold-reason:active:v1";
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final HoldReasonRepository holdReasonRepository;
    @Nullable
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public HoldReasonService(HoldReasonRepository holdReasonRepository,
                             @Nullable StringRedisTemplate redis,
                             ObjectMapper objectMapper) {
        this.holdReasonRepository = holdReasonRepository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns active hold reasons in ascending sort order.
     *
     * <p>Result is cached in Redis for {@link #CACHE_TTL}. Cache miss falls through to
     * the database. Redis unavailability falls back to a direct database read.
     */
    @PreAuthorize("isAuthenticated()")
    public List<HoldReasonResponse> getActiveReasons() {
        if (redis != null) {
            try {
                String cached = redis.opsForValue().get(CACHE_KEY);
                if (cached != null) {
                    return objectMapper.readValue(cached,
                            new TypeReference<List<HoldReasonResponse>>() {});
                }
            } catch (Exception ex) {
                log.warn("hold-reason cache read failed, falling back to database: {}", ex.getMessage());
            }
        }

        List<HoldReasonResponse> reasons = loadFromDb();

        if (redis != null) {
            try {
                redis.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(reasons), CACHE_TTL);
            } catch (Exception ex) {
                log.warn("hold-reason cache write failed: {}", ex.getMessage());
            }
        }

        return reasons;
    }

    /**
     * Validates that {@code code} exists in the active vocabulary.
     *
     * <p>Uses the cached active set. Redis unavailability falls back to the database.
     * Throws {@link InvalidHoldReasonCodeException} (maps to HTTP 400) on failure.
     */
    @PreAuthorize("isAuthenticated()")
    public void validate(String code) {
        Set<String> activeCodes = getActiveCodes();
        if (!activeCodes.contains(code)) {
            throw new InvalidHoldReasonCodeException(code);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Set<String> getActiveCodes() {
        return getActiveReasons().stream()
                .map(HoldReasonResponse::code)
                .collect(Collectors.toSet());
    }

    private List<HoldReasonResponse> loadFromDb() {
        return holdReasonRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(r -> new HoldReasonResponse(r.getCode(), r.getLabel(), r.getSortOrder()))
                .toList();
    }
}
