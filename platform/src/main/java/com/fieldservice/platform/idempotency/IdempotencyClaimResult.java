package com.fieldservice.platform.idempotency;

import java.util.UUID;

public record IdempotencyClaimResult(
        ClaimType type,
        UUID recordId,
        IdempotencyRecord record
) {
    public enum ClaimType {
        NEW,
        REPLAYED,
        CONFLICT_HASH,
        CONFLICT_IN_PROGRESS
    }

    public static IdempotencyClaimResult newKey(UUID id) {
        return new IdempotencyClaimResult(ClaimType.NEW, id, null);
    }

    public static IdempotencyClaimResult replayed(UUID id, IdempotencyRecord record) {
        return new IdempotencyClaimResult(ClaimType.REPLAYED, id, record);
    }

    public static IdempotencyClaimResult hashConflict() {
        return new IdempotencyClaimResult(ClaimType.CONFLICT_HASH, null, null);
    }

    public static IdempotencyClaimResult inProgressConflict() {
        return new IdempotencyClaimResult(ClaimType.CONFLICT_IN_PROGRESS, null, null);
    }
}
