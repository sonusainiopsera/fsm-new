package com.fieldservice.workorder.holds;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Vocabulary service for hold reason codes.
 *
 * <p>Active codes are cached in Redis with a 5-minute TTL. On cache miss or any Redis
 * failure the service falls through to the database and logs at WARN, so a degraded
 * Redis never blocks the transition path.
 */
@Service
public class HoldReasonService {

    private static final Logger log = LoggerFactory.getLogger(HoldReasonService.class);

    static final String CACHE_KEY = "fieldservice:hold_reasons:active";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final TypeReference<List<HoldReasonResponse>> LIST_TYPE =
            new TypeReference<>() {};

    private final HoldReasonRepository holdReasonRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public HoldReasonService(HoldReasonRepository holdReasonRepository,
                              @Autowired(required = false) StringRedisTemplate redis,
                              ObjectMapper objectMapper) {
        this.holdReasonRepository = holdReasonRepository;
        this.redis                = redis;
        this.objectMapper         = objectMapper;
    }

    /**
     * Returns the active hold reason vocabulary in sort order.
     * Caches the result in Redis; falls through to the database on any Redis error.
     */
    public List<HoldReasonResponse> activeReasons() {
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(CACHE_KEY);
                if (json != null) {
                    return objectMapper.readValue(json, LIST_TYPE);
                }
                List<HoldReasonResponse> reasons = loadFromDb();
                redis.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(reasons), TTL);
                return reasons;
            } catch (Exception e) {
                log.warn("Redis unavailable for hold reason cache; falling back to database", e);
            }
        }
        return loadFromDb();
    }

    /**
     * Throws {@link HoldReasonValidationException} (HTTP 400) when {@code code} is not
     * in the active vocabulary.
     */
    public void validate(String code) {
        List<HoldReasonResponse> reasons = activeReasons();
        boolean valid = reasons.stream().anyMatch(r -> r.code().equals(code));
        if (!valid) {
            throw new HoldReasonValidationException(code);
        }
    }

    private List<HoldReasonResponse> loadFromDb() {
        return holdReasonRepository.findByActiveTrueOrderBySortOrderAsc()
                .stream()
                .map(HoldReasonResponse::from)
                .toList();
    }
}
