package com.fieldservice.app.idempotency;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link RestController} with an observable side-effect counter.
 *
 * <p>The counter increments on every genuine POST to {@code /api/test/mutations}.
 * Idempotency integration tests assert that a replayed request does <em>not</em>
 * increment the counter, proving side effects are executed at most once.
 *
 * <p>Registered via {@link TestConfiguration} so it is available to
 * {@link IdempotencyIT} when {@code @Import}ed, but not present in the production
 * context.
 */
@TestConfiguration
public class TestMutationController {

    /** Observable counter: every genuine execution increments this. */
    public static final AtomicInteger COUNTER = new AtomicInteger(0);

    @Bean
    public MutationEndpoint mutationEndpoint() {
        return new MutationEndpoint();
    }

    @RestController
    @RequestMapping("/api/test/mutations")
    public static class MutationEndpoint {

        /** Resets the counter — call in {@code @BeforeEach} to isolate tests. */
        public static void reset() { COUNTER.set(0); }

        @PostMapping
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<CounterResponse> increment() {
            int value = COUNTER.incrementAndGet();
            return ResponseEntity.status(201).body(new CounterResponse(value));
        }
    }

    public record CounterResponse(int count) {}
}
