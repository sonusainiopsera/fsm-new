package com.fieldservice.identity.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed {@link StreamTicketStore} using a Lua script for atomic fetch-and-delete.
 *
 * <p>The ticket payload is serialized as a JSON string value under a namespaced key.
 * The Lua script atomically reads the value and deletes the key in a single round-trip,
 * ensuring two concurrent redemption attempts yield exactly one success.
 *
 * <p>Fail-closed: any Redis error throws {@link StoreUnavailableException}.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisStreamTicketStore implements StreamTicketStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamTicketStore.class);
    static final String KEY_PREFIX = "stream_ticket:";

    /**
     * Lua script: atomically GET the value and DELETE the key.
     * Returns the value string if the key existed, or nil/null if it did not.
     */
    private static final DefaultRedisScript<String> CONSUME_SCRIPT;

    static {
        CONSUME_SCRIPT = new DefaultRedisScript<>();
        CONSUME_SCRIPT.setScriptText(
                "local v = redis.call('GET', KEYS[1]); " +
                "if v then redis.call('DEL', KEYS[1]); return v; end; " +
                "return false"
        );
        CONSUME_SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisStreamTicketStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void store(String hashedKey, StreamTicketPayload payload, Duration ttl) {
        try {
            String json = mapper.writeValueAsString(payload);
            redis.opsForValue().set(KEY_PREFIX + hashedKey, json, ttl);
        } catch (JsonProcessingException e) {
            throw new StoreUnavailableException("Failed to serialize stream ticket payload", e);
        } catch (Exception e) {
            log.warn("alert.stream_ticket_store_unavailable key={} error={}", hashedKey, e.getMessage());
            throw new StoreUnavailableException("Stream ticket store unavailable", e);
        }
    }

    @Override
    public Optional<StreamTicketPayload> consumeAtomically(String hashedKey) {
        try {
            String json = redis.execute(CONSUME_SCRIPT, List.of(KEY_PREFIX + hashedKey));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            StreamTicketPayload payload = mapper.readValue(json,
                    new TypeReference<StreamTicketPayload>() {});
            return Optional.of(payload);
        } catch (IOException e) {
            log.warn("alert.stream_ticket_deserialize_fail key={} error={}", hashedKey, e.getMessage());
            // Treat deserialization failure as not found (corrupted ticket)
            return Optional.empty();
        } catch (Exception e) {
            log.warn("alert.stream_ticket_store_unavailable key={} error={}", hashedKey, e.getMessage());
            throw new StoreUnavailableException("Stream ticket store unavailable", e);
        }
    }
}
