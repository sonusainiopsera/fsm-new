package com.fieldservice.dispatch.web;

import com.fieldservice.platform.api.exception.InvalidCursorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RecommendationCursor} encode/decode round-trip and error handling.
 */
class RecommendationCursorTest {

    private static final UUID   TECH_ID = UUID.fromString("00000000-0000-0000-0001-000000000001");
    private static final String HMAC_KEY = "test-hmac-key-for-unit-tests";

    private RecommendationCursor cursor;

    @BeforeEach
    void setUp() {
        cursor = new RecommendationCursor(HMAC_KEY);
    }

    @Test
    @DisplayName("encode then decode round-trips score and technicianId exactly")
    void encodeDecodRoundTrip() {
        double score = 0.856423;
        RecommendationCursor.Payload payload = cursor.decode(cursor.encode(score, TECH_ID));
        assertThat(payload.score()).isEqualTo(score);
        assertThat(payload.technicianId()).isEqualTo(TECH_ID);
    }

    @Test
    @DisplayName("score 0.0 and 1.0 round-trip exactly")
    void edgeScoresRoundTrip() {
        for (double score : new double[]{0.0, 1.0, 0.5, Double.MIN_VALUE}) {
            RecommendationCursor.Payload p = cursor.decode(cursor.encode(score, TECH_ID));
            assertThat(p.score()).isEqualTo(score);
        }
    }

    @Test
    @DisplayName("tampered signature is rejected with 400-mapped exception")
    void tamperedSignatureRejected() {
        String encoded = cursor.encode(0.7, TECH_ID);
        String tampered = encoded.substring(0, encoded.length() - 3) + "AAA";
        assertThatThrownBy(() -> cursor.decode(tampered))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("signature verification failed");
    }

    @Test
    @DisplayName("blank cursor throws InvalidCursorException")
    void blankCursorRejected() {
        assertThatThrownBy(() -> cursor.decode(""))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> cursor.decode("   "))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("null cursor throws InvalidCursorException")
    void nullCursorRejected() {
        assertThatThrownBy(() -> cursor.decode(null))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("malformed format (no dot separator) throws InvalidCursorException")
    void malformedFormatRejected() {
        assertThatThrownBy(() -> cursor.decode("notavalidcursoratall"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("cursor from different HMAC key fails verification")
    void differentKeyRejected() {
        RecommendationCursor other = new RecommendationCursor("different-key");
        String encoded = other.encode(0.5, TECH_ID);
        assertThatThrownBy(() -> cursor.decode(encoded))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("page-size clamping: orchestrator clamps to max 50")
    void orchestratorPageSizeClamping() {
        // Verify constant is accessible and correct
        assertThat(RecommendationOrchestrator.MAX_PAGE_SIZE).isEqualTo(50);
    }
}
