package com.fieldservice.copilot;

import com.fieldservice.copilot.internal.GroundingContext;
import com.fieldservice.copilot.internal.PiiRedactor;
import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;
import com.fieldservice.workorder.enrichment.PriorWorkOrderSummary;
import com.fieldservice.workorder.enrichment.WorkOrderEnrichmentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-driven unit tests for {@link PiiRedactor}.
 * No Spring context — tests the component in isolation.
 */
class PiiRedactorTest {

    private PiiRedactor redactor;
    private Customer customer;
    private Site site;

    @BeforeEach
    void setUp() {
        redactor = new PiiRedactor();

        customer = new Customer();
        customer.setName("Acme Corp");
        customer.setLegalName("Acme Corporation Ltd");
        customer.setPrimaryContactName("Jane Smith");
        customer.setPrimaryContactEmail("jane.smith@acme.com");
        customer.setPrimaryContactPhone("+44 7700 900123");
        customer.setBillingAddress("10 Test Street, London");

        site = new Site();
        site.setName("North Depot");
        site.setAddress("10 Test Street, London");
        site.setPostcode("EC1A 1BB");
    }

    // ── Known-literal redaction ────────────────────────────────────────────────

    @Test
    void customerNameRedacted() {
        var result = redactFaultDescription("The unit belongs to Acme Corp and was reported by Jane Smith.");
        assertThat(result).doesNotContain("Acme Corp", "Acme Corporation Ltd", "Jane Smith");
        assertThat(result).contains("CUSTOMER_1").contains("CONTACT_1");
    }

    @Test
    void emailRedacted() {
        var result = redactFaultDescription("Contact jane.smith@acme.com for details.");
        assertThat(result).doesNotContain("jane.smith@acme.com");
    }

    @Test
    void phoneRedacted() {
        var result = redactFaultDescription("Call +44 7700 900123 if the fault recurs.");
        assertThat(result).doesNotContain("+44 7700 900123");
    }

    @Test
    void addressRedacted() {
        var result = redactFaultDescription("Equipment installed at 10 Test Street, London, site North Depot.");
        assertThat(result).doesNotContain("10 Test Street, London", "North Depot");
    }

    @Test
    void postcodeRedactedByPattern() {
        var result = redactFaultDescription("The site postcode is EC1A 1BB — call ahead.");
        assertThat(result).doesNotContain("EC1A 1BB");
    }

    @Test
    void multipleOccurrencesAllRedacted() {
        var result = redactFaultDescription("Acme Corp called. Acme Corp rep Jane Smith at jane.smith@acme.com.");
        assertThat(result).doesNotContain("Acme Corp", "Jane Smith", "jane.smith@acme.com");
    }

    // ── Table-driven parameterized tests ──────────────────────────────────────

    static Stream<Arguments> piiCases() {
        return Stream.of(
                Arguments.of("customer name",    "Equipment owned by Acme Corp.",       "Acme Corp"),
                Arguments.of("legal name",       "Invoice from Acme Corporation Ltd.",  "Acme Corporation Ltd"),
                Arguments.of("contact name",     "Jane Smith reported the issue.",      "Jane Smith"),
                Arguments.of("primary email",    "Email jane.smith@acme.com.",          "jane.smith@acme.com"),
                Arguments.of("phone pattern",    "Call +44 7700 900123 now.",           "+44 7700 900123"),
                Arguments.of("billing address",  "At 10 Test Street, London.",          "10 Test Street, London"),
                Arguments.of("site name",        "Located at North Depot.",             "North Depot"),
                Arguments.of("postcode pattern", "Postcode: EC1A 1BB.",                 "EC1A 1BB")
        );
    }

    @ParameterizedTest(name = "{0}: must not appear in redacted output")
    @MethodSource("piiCases")
    void piiLiteralAbsentFromOutput(String label, String input, String piiValue) {
        String result = redactFaultDescription(input);
        assertThat(result).as(label).doesNotContain(piiValue);
    }

    // ── Diacritic-insensitive matching ────────────────────────────────────────

    @Test
    void diacriticVariantRedacted() {
        customer.setName("Müller GmbH");
        var result = redactFaultDescription("Equipment from Muller GmbH failed.");
        // normalised "muller gmbh" should match both spellings
        assertThat(result).doesNotContain("Muller GmbH");
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void nullFaultDescriptionReturnedAsNull() {
        var result = redactFaultDescription(null);
        assertThat(result).isNull();
    }

    @Test
    void emptyFaultDescriptionReturnedAsEmpty() {
        var result = redactFaultDescription("");
        assertThat(result).isEmpty();
    }

    @Test
    void technicalContentNotCorrupted() {
        String text = "Replaced bearing P/N XB-4412. Torque 45 Nm. RPM 3600.";
        var result = redactFaultDescription(text);
        assertThat(result).contains("XB-4412").contains("Torque 45 Nm").contains("RPM 3600");
    }

    @Test
    void textExceedingMaxLenTruncated() {
        String longText = "x".repeat(3000);
        var result = redactFaultDescription(longText);
        // default max is 2000 in CopilotProperties — but redact() takes explicit limit
        assertThat(result).contains("[TRUNCATED]");
    }

    // ── Hard negative: no seeded PII literal in full serialised output ────────

    @Test
    void hardNegative_noSeededPiiInSerializedPayload() {
        var priorWo = new PriorWorkOrderSummary(
                UUID.randomUUID(),
                "Jane Smith reported boiler fault at North Depot site (EC1A 1BB).",
                "Fixed. Contacted jane.smith@acme.com and Acme Corp billing.",
                List.of("Thermocouple", "Gasket set"));

        var ctx = buildContext("Fault reported by jane.smith@acme.com at 10 Test Street, London.", List.of(priorWo));
        var redacted = redactor.redact(ctx, 2000);

        // Serialize everything to a flat string and assert no PII
        String serialized = redacted.faultDescription()
                + redacted.priorWorkOrders().stream()
                    .map(p -> p.faultDescription() + " " + p.resolutionNotes())
                    .reduce("", (a, b) -> a + " " + b);

        assertThat(serialized)
                .doesNotContain("jane.smith@acme.com")
                .doesNotContain("Jane Smith")
                .doesNotContain("Acme Corp")
                .doesNotContain("North Depot")
                .doesNotContain("EC1A 1BB")
                .doesNotContain("10 Test Street, London");
    }

    // ── Sufficiency evaluator tests (co-located for convenience) ──────────────

    @Nested
    class GroundingSufficiencyEvaluatorTest {

        private com.fieldservice.copilot.internal.GroundingSufficiencyEvaluator evaluator;

        @BeforeEach
        void setUp() {
            evaluator = new com.fieldservice.copilot.internal.GroundingSufficiencyEvaluator();
        }

        @Test
        void noAsset_returnsInsufficient() {
            var ctx = enrichmentContext(null, null, List.of());
            var verdict = evaluator.evaluate(ctx);
            assertThat(verdict.verdict()).isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.INSUFFICIENT);
            assertThat(verdict.reasonCode()).isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.ReasonCode.NO_ASSET_IDENTITY);
        }

        @Test
        void assetPresentNoPriorNoFault_returnsInsufficient() {
            var ctx = enrichmentContext(mockAsset(), null, List.of());
            var verdict = evaluator.evaluate(ctx);
            assertThat(verdict.verdict()).isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.INSUFFICIENT);
        }

        @Test
        void assetAndFaultButNoPriorHistory_returnsInsufficient() {
            var ctx = enrichmentContext(mockAsset(), "Boiler not heating", List.of());
            var verdict = evaluator.evaluate(ctx);
            assertThat(verdict.verdict()).isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.INSUFFICIENT);
            assertThat(verdict.reasonCode()).isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.ReasonCode.NO_PRIOR_SERVICE_HISTORY);
        }

        @Test
        void assetAndPriorHistory_returnsSufficient() {
            var prior = new PriorWorkOrderSummary(UUID.randomUUID(), "Prior fault", "Fixed it", List.of());
            var ctx = enrichmentContext(mockAsset(), null, List.of(prior));
            var verdict = evaluator.evaluate(ctx);
            assertThat(verdict.verdict()).isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.SUFFICIENT);
        }

        @Test
        void assetAndFaultAndPriorHistory_returnsSufficient() {
            var prior = new PriorWorkOrderSummary(UUID.randomUUID(), "Previous fault", "Resolved", List.of());
            var ctx = enrichmentContext(mockAsset(), "Current fault", List.of(prior));
            var verdict = evaluator.evaluate(ctx);
            assertThat(verdict.verdict()).isEqualTo(com.fieldservice.copilot.api.SufficiencyVerdict.SUFFICIENT);
        }

        private WorkOrderEnrichmentContext enrichmentContext(Asset asset, String fault, List<PriorWorkOrderSummary> prior) {
            return new WorkOrderEnrichmentContext(UUID.randomUUID(), fault, asset,
                    PiiRedactorTest.this.site, PiiRedactorTest.this.customer, prior);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String redactFaultDescription(String faultDesc) {
        var ctx = buildContext(faultDesc, List.of());
        return redactor.redact(ctx, 2000).faultDescription();
    }

    private GroundingContext buildContext(String faultDesc, List<PriorWorkOrderSummary> prior) {
        var enrichment = new WorkOrderEnrichmentContext(
                UUID.randomUUID(), faultDesc, null, site, customer, prior);
        return new GroundingContext(enrichment);
    }

    private static Asset mockAsset() {
        Asset a = new Asset();
        a.setName("Boiler Unit A");
        a.setAssetType("HVAC");
        return a;
    }
}
