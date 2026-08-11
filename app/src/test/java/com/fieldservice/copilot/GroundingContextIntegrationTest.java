package com.fieldservice.copilot;

import com.fieldservice.copilot.api.GroundingUnavailableException;
import com.fieldservice.copilot.api.RedactedPrompt;
import com.fieldservice.copilot.internal.PromptAssembler;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.jdbc.Sql;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration test for the copilot grounding pipeline.
 *
 * <p>Seeds an asset with prior work orders (V127 fixture) and drives the full pipeline
 * through {@link PromptAssembler}, asserting:
 * <ul>
 *   <li>Grounding basis lists correct contributing work order IDs</li>
 *   <li>No seeded PII literal appears in the assembled prompt</li>
 *   <li>Sufficiency verdict raises {@link GroundingUnavailableException} when asset is absent</li>
 *   <li>Access control: a technician cannot obtain grounding for another tech's work order</li>
 * </ul>
 *
 * <p>Uses fixture IDs from V127__grounding_copilot_fixtures.sql.
 */
class GroundingContextIntegrationTest extends AbstractIntegrationTest {

    // ── Fixture UUIDs (from V127__grounding_copilot_fixtures.sql) ─────────────

    private static final UUID WO_WITH_ASSET_AND_HISTORY =
            UUID.fromString("cc000000-0000-0000-0000-000000000030"); // assigned tech1

    private static final UUID WO_NO_ASSET =
            UUID.fromString("cc000000-0000-0000-0000-000000000033"); // assigned tech1

    private static final UUID WO_TECH2_ONLY =
            UUID.fromString("cc000000-0000-0000-0000-000000000034"); // assigned tech2

    private static final UUID ASSET_ID =
            UUID.fromString("cc000000-0000-0000-0000-000000000020");

    private static final UUID PRIOR_WO_1 =
            UUID.fromString("cc000000-0000-0000-0000-000000000031");

    private static final UUID PRIOR_WO_2 =
            UUID.fromString("cc000000-0000-0000-0000-000000000032");

    @Autowired
    private PromptAssembler promptAssembler;

    // ── AC-1: retriever assembles context with asset identity + prior history ──

    @Test
    @WithMockUser(username = "aaaaaaaa-0000-0000-0000-000000000011", roles = "TECHNICIAN",
            extraAttributes = {"technicianId=00000000-0000-0000-0000-000000000011"})
    void assembledPromptIsGroundedAndRedacted() {
        RedactedPrompt result = promptAssembler.assemble(
                WO_WITH_ASSET_AND_HISTORY, "Why is the boiler overheating?");

        // AC-8: basis lists the asset and contributing prior WOs
        assertThat(result.groundingBasis().assetId()).isEqualTo(ASSET_ID);
        assertThat(result.groundingBasis().contributingWorkOrderIds())
                .containsExactlyInAnyOrder(PRIOR_WO_1, PRIOR_WO_2);

        // AC-7 / AC-5: no seeded PII literal in the assembled prompt
        String prompt = result.request().prompt();
        assertThat(prompt)
                .doesNotContain("Patricia Whitmore")
                .doesNotContain("patricia.whitmore@example.com")
                .doesNotContain("+15555550300")
                .doesNotContain("42 Fixture Lane")
                .doesNotContain("Whitmore Industrial")
                .doesNotContain("EX1 4BB");

        // AC-9: user query is fenced
        assertThat(prompt).contains("UNTRUSTED DATA");

        // Prompt contains technical grounding content (non-PII)
        assertThat(prompt).contains("BOILER");
    }

    // ── AC-4: INSUFFICIENT verdict when asset is absent ──────────────────────

    @Test
    @WithMockUser(username = "aaaaaaaa-0000-0000-0000-000000000011", roles = "TECHNICIAN",
            extraAttributes = {"technicianId=00000000-0000-0000-0000-000000000011"})
    void groundingUnavailableWhenNoAsset() {
        assertThatThrownBy(() -> promptAssembler.assemble(WO_NO_ASSET, "Any question"))
                .isInstanceOf(GroundingUnavailableException.class)
                .satisfies(ex -> {
                    GroundingUnavailableException gue = (GroundingUnavailableException) ex;
                    assertThat(gue.getReasonCode())
                            .isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.ReasonCode.NO_ASSET_IDENTITY);
                });
    }

    // ── AC-3: access control — tech1 cannot obtain grounding for tech2's WO ──

    @Test
    @WithMockUser(username = "aaaaaaaa-0000-0000-0000-000000000011", roles = "TECHNICIAN",
            extraAttributes = {"technicianId=00000000-0000-0000-0000-000000000011"})
    void tech1CannotGroundTech2WorkOrder() {
        assertThatThrownBy(() -> promptAssembler.assemble(WO_TECH2_ONLY, "Some question"))
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    // ── AC-10: unconditional redaction — no bypass path ───────────────────────

    @Test
    @WithMockUser(username = "aaaaaaaa-0000-0000-0000-000000000011", roles = "TECHNICIAN",
            extraAttributes = {"technicianId=00000000-0000-0000-0000-000000000011"})
    void promptPayloadContainsNoPiiFromAnyField() {
        RedactedPrompt result = promptAssembler.assemble(
                WO_WITH_ASSET_AND_HISTORY, "Check parts history");

        // Serialize the entire request to a string (prompt + context map)
        String fullPayload = result.request().prompt()
                + result.request().context().toString();

        assertThat(fullPayload)
                .doesNotContain("Patricia Whitmore")
                .doesNotContain("patricia.whitmore@example.com")
                .doesNotContain("+15555550300")
                .doesNotContain("42 Fixture Lane, Springfield")
                .doesNotContain("EX1 4BB")
                .doesNotContain("Whitmore North Plant");
    }

    // ── Redaction report tracks substitution counts ───────────────────────────

    @Test
    @WithMockUser(username = "aaaaaaaa-0000-0000-0000-000000000011", roles = "TECHNICIAN",
            extraAttributes = {"technicianId=00000000-0000-0000-0000-000000000011"})
    void redactionReportCountsNonZero() {
        RedactedPrompt result = promptAssembler.assemble(
                WO_WITH_ASSET_AND_HISTORY, "Why is the boiler overheating?");

        // The fixture fault description includes PII — report should record at least one substitution
        assertThat(result.redactionReport().totalSubstitutions()).isGreaterThan(0);
    }
}
