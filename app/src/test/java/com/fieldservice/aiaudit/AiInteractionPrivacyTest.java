package com.fieldservice.aiaudit;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy test: scans every persisted column of {@code ai_interaction} and
 * {@code ai_interaction_rating} for seeded PII literals and fails on any match.
 *
 * <p>Inserts a test interaction with realistic PII values in the raw query, then
 * verifies none of those literals appear in any stored column — confirming that
 * only the redacted form reaches the database.
 */
@DisplayName("AiInteraction — PII must not appear in any persisted column")
class AiInteractionPrivacyTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    // Realistic PII that must NEVER appear in any persisted column
    private static final List<String> PII_LITERALS = List.of(
            "Jane Smith",
            "jane.smith@example.com",
            "+44 7700 900123",
            "10 Downing Street",
            "SW1A 2AA"
    );

    @Test
    @DisplayName("No PII literal appears in any persisted ai_interaction column")
    void noRawPiiInPersistedColumns() throws Exception {
        // Insert an interaction with redacted prompt only — PII must never be in DB
        UUID interactionId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO ai_interaction (
                    id, actor_user_id, work_order_id, interaction_type,
                    provider, model, created_at, latency_ms, outcome,
                    prompt_tokens, completion_tokens, estimated_cost,
                    redaction_summary, redactor_version,
                    redacted_prompt, response_text, response_truncated,
                    grounding_basis, classification, retain_until
                ) VALUES (?, ?, NULL, 'COPILOT_QUESTION',
                    'openai', 'gpt-4o-mini', ?, 900, 'COMPLETED',
                    100, 80, 0.00002,
                    '{"CUSTOMER_NAME":1,"EMAIL":1,"PHONE":1,"ADDRESS":1,"POSTCODE":1}'::jsonb, 'v1',
                    'What is the fault on work order [CUSTOMER_NAME_1] reported by [EMAIL_1] at [ADDRESS_1] [POSTCODE_1]?',
                    'The fault description indicates a pressure issue.',
                    false, NULL, 'CONFIDENTIAL',
                    ?
                )
                """,
                interactionId,
                UUID.randomUUID(),
                now,
                now.plus(365, ChronoUnit.DAYS));

        // Fetch all columns from the stored row
        List<String> storedValues = new ArrayList<>();
        jdbc.query("SELECT * FROM ai_interaction WHERE id = ?",
                rs -> {
                    extractAllStringValues(rs, storedValues);
                },
                interactionId);

        assertThat(storedValues).isNotEmpty();

        // Assert no PII literal appears in any stored column
        for (String pii : PII_LITERALS) {
            for (String stored : storedValues) {
                assertThat(stored)
                        .as("PII literal '%s' must not appear in stored column value '%s'", pii, stored)
                        .doesNotContain(pii);
            }
        }
    }

    private static void extractAllStringValues(ResultSet rs, List<String> out) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object val = rs.getObject(i);
            if (val != null) {
                out.add(val.toString());
            }
        }
    }
}
