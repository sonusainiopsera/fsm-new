package com.fieldservice.platform.idempotency;

import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IdempotencyKeyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyService.class);

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> {
        Integer status = rs.getObject("response_status") != null
                ? rs.getInt("response_status") : null;
        return new IdempotencyRecord(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("user_id"),
                rs.getString("endpoint"),
                rs.getString("request_hash"),
                status,
                rs.getString("response_body"),
                rs.getString("response_headers"),
                IdempotencyKeyState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()
        );
    };

    private final JdbcTemplate jdbc;
    private final IdempotencyKeyProperties properties;

    public IdempotencyKeyService(JdbcTemplate jdbc, IdempotencyKeyProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public IdempotencyClaimResult claim(String key, String userId, String endpoint,
                                         String requestHash) {
        return claimWithRetry(key, userId, endpoint, requestHash, 3);
    }

    private IdempotencyClaimResult claimWithRetry(String key, String userId, String endpoint,
                                                    String requestHash, int retries) {
        UUID id = UuidV7.generate();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTtl());

        try {
            jdbc.update(
                    "INSERT INTO idempotency_key " +
                    "(id, idempotency_key, user_id, endpoint, request_hash, state, created_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)",
                    id, key, userId, endpoint, requestHash,
                    Timestamp.from(now), Timestamp.from(expiresAt));
            return IdempotencyClaimResult.newKey(id);
        } catch (DuplicateKeyException e) {
            return handleDuplicate(key, userId, endpoint, requestHash, retries);
        }
    }

    private IdempotencyClaimResult handleDuplicate(String key, String userId, String endpoint,
                                                    String requestHash, int retries) {
        List<IdempotencyRecord> rows = jdbc.query(
                "SELECT * FROM idempotency_key WHERE idempotency_key = ? AND user_id = ? AND endpoint = ?",
                ROW_MAPPER, key, userId, endpoint);

        if (rows.isEmpty()) {
            // Race: deleted between our INSERT and this SELECT
            if (retries > 0) {
                return claimWithRetry(key, userId, endpoint, requestHash, retries - 1);
            }
            return IdempotencyClaimResult.inProgressConflict();
        }

        IdempotencyRecord record = rows.get(0);

        // Expired record: delete and retry
        if (record.expiresAt().isBefore(Instant.now())) {
            int deleted = jdbc.update(
                    "DELETE FROM idempotency_key WHERE id = ? AND expires_at < now()",
                    record.id());
            if (deleted > 0 && retries > 0) {
                return claimWithRetry(key, userId, endpoint, requestHash, retries - 1);
            }
        }

        switch (record.state()) {
            case IN_PROGRESS -> {
                // Stale IN_PROGRESS (crashed request): reclaim after lease expiry
                Instant leaseExpiry = record.createdAt().plus(properties.getLeaseTimeout());
                if (leaseExpiry.isBefore(Instant.now())) {
                    int deleted = jdbc.update(
                            "DELETE FROM idempotency_key WHERE id = ? AND state = 'IN_PROGRESS'",
                            record.id());
                    if (deleted > 0 && retries > 0) {
                        return claimWithRetry(key, userId, endpoint, requestHash, retries - 1);
                    }
                }
                return IdempotencyClaimResult.inProgressConflict();
            }
            case COMPLETED -> {
                if (!record.requestHash().equals(requestHash)) {
                    return IdempotencyClaimResult.hashConflict();
                }
                return IdempotencyClaimResult.replayed(record.id(), record);
            }
            case NON_REPLAYABLE -> {
                // Body was too large to store; replay is not possible
                return IdempotencyClaimResult.hashConflict();
            }
            default -> {
                return IdempotencyClaimResult.inProgressConflict();
            }
        }
    }

    public void complete(UUID id, int status, byte[] body, String headersJson) {
        String bodyStr = (body != null && body.length > 0)
                ? new String(body, StandardCharsets.UTF_8) : null;
        jdbc.update(
                "UPDATE idempotency_key " +
                "SET state = 'COMPLETED', response_status = ?, response_body = ?, response_headers = ? " +
                "WHERE id = ?",
                status, bodyStr, headersJson, id);
    }

    public void markNonReplayable(UUID id) {
        jdbc.update("UPDATE idempotency_key SET state = 'NON_REPLAYABLE' WHERE id = ?", id);
    }

    public void release(UUID id) {
        jdbc.update("DELETE FROM idempotency_key WHERE id = ?", id);
    }

    @Transactional
    public int purgeExpired(int batchSize) {
        return jdbc.update(
                "DELETE FROM idempotency_key WHERE id IN " +
                "(SELECT id FROM idempotency_key WHERE expires_at < now() LIMIT ?)",
                batchSize);
    }
}
