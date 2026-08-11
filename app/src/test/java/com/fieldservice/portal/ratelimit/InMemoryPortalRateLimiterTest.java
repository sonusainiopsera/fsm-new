package com.fieldservice.portal.ratelimit;

import com.fieldservice.platform.exception.RateLimitedException;
import com.fieldservice.portal.config.PortalSubmissionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InMemoryPortalRateLimiter} (no Spring context).
 */
class InMemoryPortalRateLimiterTest {

    private static final UUID ACCOUNT_A = UUID.randomUUID();
    private static final UUID ACCOUNT_B = UUID.randomUUID();
    private static final String IP = "10.0.0.1";
    private static final String IP2 = "10.0.0.2";

    private InMemoryPortalRateLimiter limiter;

    @BeforeEach
    void setUp() {
        PortalSubmissionProperties props = new PortalSubmissionProperties();
        props.setRateLimitMax(3);
        props.setRateLimitWindowSeconds(60L);
        limiter = new InMemoryPortalRateLimiter(props);
    }

    @Test
    @DisplayName("Requests within the limit are permitted")
    void withinLimit_permitted() {
        assertThatCode(() -> {
            limiter.checkAndRecord(ACCOUNT_A, IP);
            limiter.checkAndRecord(ACCOUNT_A, IP);
            limiter.checkAndRecord(ACCOUNT_A, IP);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Request exceeding the limit throws RateLimitedException (AC-7)")
    void exceedingLimit_throwsRateLimitedException() {
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);

        assertThatThrownBy(() -> limiter.checkAndRecord(ACCOUNT_A, IP))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("Different account IDs have independent counters")
    void differentAccounts_independentCounters() {
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);

        // ACCOUNT_B should not be affected
        assertThatCode(() -> limiter.checkAndRecord(ACCOUNT_B, IP))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Different IPs for the same account have independent counters")
    void differentIps_independentCounters() {
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);

        // Different IP for ACCOUNT_A should not be affected
        assertThatCode(() -> limiter.checkAndRecord(ACCOUNT_A, IP2))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("RateLimitedException carries retryAfterSeconds")
    void exception_carriesRetryAfterSeconds() {
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);
        limiter.checkAndRecord(ACCOUNT_A, IP);

        assertThatThrownBy(() -> limiter.checkAndRecord(ACCOUNT_A, IP))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(ex -> {
                    long retryAfter = ((RateLimitedException) ex).getRetryAfterSeconds();
                    assertThat(retryAfter).isGreaterThan(0L);
                });
    }
}
