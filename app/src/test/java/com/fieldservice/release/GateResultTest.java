package com.fieldservice.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GateResult} — no network access.
 */
@DisplayName("GateResult unit tests")
class GateResultTest {

    @Test
    @DisplayName("pass() creates PASS result with passed()=true, failed()=false")
    void pass_result() {
        GateResult r = GateResult.pass("TestGate", Duration.ofMillis(42), "all good");
        assertThat(r.status()).isEqualTo(GateResult.Status.PASS);
        assertThat(r.passed()).isTrue();
        assertThat(r.failed()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(r.gateName()).isEqualTo("TestGate");
        assertThat(r.duration()).isEqualTo(Duration.ofMillis(42));
        assertThat(r.detail()).isEqualTo("all good");
    }

    @Test
    @DisplayName("fail() creates FAIL result with failed()=true, passed()=false")
    void fail_result() {
        GateResult r = GateResult.fail("TestGate", Duration.ofMillis(100), "invariant broken");
        assertThat(r.status()).isEqualTo(GateResult.Status.FAIL);
        assertThat(r.failed()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(r.skipped()).isFalse();
    }

    @Test
    @DisplayName("skip() creates SKIP result with zero duration")
    void skip_result() {
        GateResult r = GateResult.skip("TestGate", "dry-run mode");
        assertThat(r.status()).isEqualTo(GateResult.Status.SKIP);
        assertThat(r.skipped()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(r.failed()).isFalse();
        assertThat(r.duration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("setupError() is treated as failed")
    void setupError_isFailed() {
        GateResult r = GateResult.setupError("TestGate", "missing env var");
        assertThat(r.status()).isEqualTo(GateResult.Status.SETUP_ERROR);
        assertThat(r.failed()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(r.skipped()).isFalse();
    }
}
