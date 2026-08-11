package com.fieldservice.api;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only controller with a monotonically incrementing counter.
 *
 * <p>Used by {@link com.fieldservice.idempotency.IdempotencyIntegrationTest} to verify
 * idempotency: a genuine first call increments the counter, a replayed response must not.
 */
@TestComponent
@RestController
@RequestMapping("/test/counter")
@Profile("test")
public class TestCounterController {

    final AtomicInteger callCount = new AtomicInteger(0);

    /** Increments the call counter and returns the new value. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> increment(@RequestBody(required = false) Map<String, Object> body) {
        int value = callCount.incrementAndGet();
        return ResponseEntity.ok(Map.of("count", value, "payload", body != null ? body : Map.of()));
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void reset() {
        callCount.set(0);
    }
}
