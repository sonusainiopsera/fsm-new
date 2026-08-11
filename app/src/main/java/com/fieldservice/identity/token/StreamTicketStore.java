package com.fieldservice.identity.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis-backed store for single-use SSE stream tickets.
 *
 * <p>Key format: {@code stream:ticket:{sha256hex(ticketValue)}} — the plaintext ticket
 * is NEVER stored in Redis; only a SHA-256 hex digest appears as the key.
 *
 * <p>Redemption is atomic: a Lua script fetches the hash payload and deletes the key in
 * a single round-trip, preventing any replay race between validation and consumption.
 *
 * <p>TTL is fixed at 60 seconds and cannot be relaxed.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class StreamTicketStore {

    static final Duration TICKET_TTL = Duration.ofSeconds(60);
    static final String KEY_PREFIX = "stream:ticket:";

    // Atomically fetches all hash fields and deletes the key in one round-trip.
    // Returns nil (→ null List) if the key does not exist (expired or never stored).
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ATOMIC_REDEEM_SCRIPT;

    static {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(
            "local d = redis.call('HGETALL', KEYS[1])\n" +
            "if #d == 0 then return nil end\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "return d"
        );
        script.setResultType(List.class);
        ATOMIC_REDEEM_SCRIPT = script;
    }

    private final StringRedisTemplate redis;

    public StreamTicketStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Stores the ticket payload under a SHA-256 hash of the ticket value with a 60-second TTL.
     * The plaintext {@code ticketValue} is never written to Redis.
     */
    public void store(String ticketValue, TicketPayload payload) {
        String key = keyFor(ticketValue);
        Map<String, String> hash = Map.of(
            "userId",      payload.userId(),
            "authorities", payload.authorities(),
            "clientIp",    payload.clientIp(),
            "issuedAt",    payload.issuedAt(),
            "jti",         payload.jti()
        );
        redis.opsForHash().putAll(key, hash);
        redis.expire(key, TICKET_TTL);
    }

    /**
     * Atomically redeems the ticket: if the key exists the payload is returned and the key
     * deleted in a single Lua round-trip. Returns empty if the ticket has expired or was
     * already consumed (replay).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<TicketPayload> redeem(String ticketValue) {
        String key = keyFor(ticketValue);
        List<String> result = (List<String>) redis.execute(ATOMIC_REDEEM_SCRIPT, List.of(key));
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        // HGETALL returns alternating [field, value, field, value, ...] pairs
        Map<String, String> hash = new HashMap<>();
        for (int i = 0; i + 1 < result.size(); i += 2) {
            hash.put(result.get(i), result.get(i + 1));
        }
        return Optional.of(new TicketPayload(
            hash.get("userId"),
            hash.get("authorities"),
            hash.get("clientIp"),
            hash.get("issuedAt"),
            hash.getOrDefault("jti", "")
        ));
    }

    /** Redis key for a given ticket value (SHA-256 hex of the plaintext). */
    static String keyFor(String ticketValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ticketValue.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record TicketPayload(
            String userId,
            String authorities,
            String clientIp,
            String issuedAt,
            String jti) {}
}
