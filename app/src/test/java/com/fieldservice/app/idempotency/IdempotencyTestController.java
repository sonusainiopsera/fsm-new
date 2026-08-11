package com.fieldservice.app.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only controller with an observable side-effect counter used by idempotency integration tests.
 */
@RestController
@RequestMapping("/test/idempotency")
public class IdempotencyTestController {

    static final AtomicInteger COUNTER = new AtomicInteger(0);

    /** When true, /increment returns 500 on next call; resets to false after one failure. */
    static final AtomicBoolean FAIL_NEXT = new AtomicBoolean(false);

    @PostMapping("/increment")
    public ResponseEntity<Map<String, Integer>> increment(
            @RequestParam(required = false) String input) {
        int count = COUNTER.incrementAndGet();
        if (FAIL_NEXT.compareAndSet(true, false)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("count", count));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("count", count));
    }
}
