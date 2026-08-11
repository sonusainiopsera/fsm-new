package com.fieldservice.platform.idempotency;

public enum IdempotencyKeyState {
    IN_PROGRESS,
    COMPLETED,
    NON_REPLAYABLE
}
