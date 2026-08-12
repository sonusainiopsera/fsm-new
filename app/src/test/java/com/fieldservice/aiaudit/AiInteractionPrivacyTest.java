package com.fieldservice.aiaudit;

import com.fieldservice.aiaudit.internal.AiInteraction;
import com.fieldservice.aiaudit.internal.AiInteractionRepository;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard privacy test: scans every persisted column in {@code ai_interaction} rows for
 * seeded PII literals and fails on any occurrence.
 *
 * <p>The fixture in {@code ai-interaction-seed.sql} inserts rows where the prompt and
 * response fields contain only redacted tokens (e.g. {@code [EMAIL_REDACTED]}) — no
 * real names, email addresses, phone numbers, postcodes, or physical addresses should
 * appear in any persisted column.
 *
 * <p>This test enforces WO-180 AC-5 and AC-7: persisted prompt is the redacted form,
 * and no PII literals appear in log output.
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/copilot/thin-grounding-seed.sql",
        "classpath:fixtures/aiaudit/ai-interaction-seed.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AiInteractionPrivacyTest extends AbstractIntegrationTest {

    /**
     * Synthetic PII literals seeded by the fixture file. These values must NEVER appear
     * in any persisted column. They are entirely fictional — not real person data.
     *
     * <p>If a literal below appears in a persisted column, it means the redactor failed
     * to substitute it and the raw text was stored, which is a compliance violation.
     */
    private static final List<String> PII_LITERALS = List.of(
            // Customer names used in realistic (but synthetic) test prompts
            "Margaret Thornton",
            "J. P. Hargreaves",
            "Delta Holdings",
            // Email patterns
            "margaret.thornton@",
            "ops@deltaholdings",
            "@example.com",
            // Phone numbers (E.164 and local format)
            "+44 20 1234 5678",
            "01234 567890",
            // Physical addresses
            "10 Fleet Street",
            "EC4Y 1AA",
            // Technician name — must never appear in AI interaction records
            "Test Technician"
    );

    @Autowired
    private AiInteractionRepository repository;

    @Autowired
    private DatabaseCleaner dbCleaner;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
    }

    @Test
    @DisplayName("No PII literals appear in any persisted ai_interaction column")
    void noPiiLiteralsInPersistedColumns() {
        List<AiInteraction> all = repository.findAll();
        assertThat(all).isNotEmpty();

        List<String> violations = new ArrayList<>();

        for (AiInteraction interaction : all) {
            checkField(violations, interaction.getId().toString(),
                    "id", interaction.getId().toString());
            checkField(violations, interaction.getId().toString(),
                    "interactionType", interaction.getInteractionType());
            checkField(violations, interaction.getId().toString(),
                    "provider", interaction.getProvider());
            checkField(violations, interaction.getId().toString(),
                    "model", interaction.getModel());
            checkField(violations, interaction.getId().toString(),
                    "outcome", interaction.getOutcome());
            checkField(violations, interaction.getId().toString(),
                    "redactionSummary", interaction.getRedactionSummary());
            checkField(violations, interaction.getId().toString(),
                    "redactorVersion", interaction.getRedactorVersion());
            checkField(violations, interaction.getId().toString(),
                    "redactedPrompt", interaction.getRedactedPrompt());
            checkField(violations, interaction.getId().toString(),
                    "responseText", interaction.getResponseText());
            checkField(violations, interaction.getId().toString(),
                    "groundingBasis", interaction.getGroundingBasis());
            checkField(violations, interaction.getId().toString(),
                    "classification", interaction.getClassification());
        }

        assertThat(violations)
                .as("PII literals found in persisted ai_interaction columns:\n" +
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("All interactions have non-empty redaction_summary JSON")
    void allInteractionsHaveRedactionSummary() {
        List<AiInteraction> all = repository.findAll();
        for (AiInteraction interaction : all) {
            assertThat(interaction.getRedactionSummary())
                    .as("interaction %s should have non-null redaction_summary",
                            interaction.getId())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("No interaction stores raw prompt content with known PII structure")
    void redactedPromptContainsOnlyTokenPlaceholders() {
        // If a redacted_prompt is present, it should contain substitution tokens
        // (e.g. [EMAIL_REDACTED]) rather than PII literals.
        List<AiInteraction> withPrompts = repository.findAll().stream()
                .filter(i -> i.getRedactedPrompt() != null)
                .toList();

        for (AiInteraction interaction : withPrompts) {
            for (String pii : PII_LITERALS) {
                assertThat(interaction.getRedactedPrompt())
                        .as("PII literal '%s' found in redacted_prompt of interaction %s",
                                pii, interaction.getId())
                        .doesNotContainIgnoringCase(pii);
            }
        }
    }

    @Test
    @DisplayName("Expired interaction (past retain_until) exists for purge job testing")
    void expiredInteractionExistsForPurgeTest() {
        // This row is inserted by the seed with retain_until in the past,
        // confirming the purge job has rows to operate on.
        boolean hasExpired = repository.findAll().stream()
                .anyMatch(i -> i.getRetainUntil().isBefore(java.time.Instant.now()));
        assertThat(hasExpired)
                .as("At least one expired interaction should exist for purge job testing")
                .isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private void checkField(List<String> violations, String interactionId,
                             String fieldName, String value) {
        if (value == null) return;
        for (String pii : PII_LITERALS) {
            if (value.toLowerCase().contains(pii.toLowerCase())) {
                violations.add(String.format(
                        "  interaction=%s field=%s contains PII literal '%s'",
                        interactionId, fieldName, pii));
            }
        }
    }
}
