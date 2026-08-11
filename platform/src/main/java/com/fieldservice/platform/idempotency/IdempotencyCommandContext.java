package com.fieldservice.platform.idempotency;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

/**
 * Request-scoped carrier for the validated idempotency key.
 * Domain services inject this (via the scoped proxy) to access the key for
 * ledger-level natural-key deduplication, keeping HTTP and domain idempotency consistent.
 */
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class IdempotencyCommandContext {

    private String idempotencyKey;

    public void set(String key) {
        this.idempotencyKey = key;
    }

    public String get() {
        return idempotencyKey;
    }

    public boolean hasKey() {
        return idempotencyKey != null;
    }
}
