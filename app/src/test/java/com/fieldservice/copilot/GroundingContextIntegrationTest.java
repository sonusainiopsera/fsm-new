package com.fieldservice.copilot;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamCallback;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import com.fieldservice.aigateway.fake.FakeAiGatewayAdapter;
import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.copilot.api.CopilotResponse;
import com.fieldservice.copilot.internal.GroundingUnavailableException;
import com.fieldservice.copilot.internal.PromptAssembler;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the copilot grounding pipeline.
 *
 * <p>Seeds an asset with prior work orders, invokes {@link PromptAssembler} through
 * the full Spring context, and asserts that the outbound payload captured by the
 * recording gateway is grounded, redacted and carries a correct basis.
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/copilot/grounding-seed.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Import(GroundingContextIntegrationTest.CopilotTestConfig.class)
class GroundingContextIntegrationTest extends AbstractIntegrationTest {

    // WO-177 UUIDs from grounding-seed.sql
    static final UUID WO_ASSET1_CURRENT = UUID.fromString("00000000-0000-7177-8000-000000000031");
    static final UUID WO_ASSET2_CURRENT = UUID.fromString("00000000-0000-7177-8000-000000000035");
    static final UUID WO_ASSET3_CURRENT = UUID.fromString("00000000-0000-7177-8000-000000000036");
    static final UUID WO_TECH2_ASSET1   = UUID.fromString("00000000-0000-7177-8000-000000000038");
    static final UUID ASSET1_ID         = UUID.fromString("00000000-0000-7177-8000-000000000021");
    static final UUID PRIOR_WO_1        = UUID.fromString("00000000-0000-7177-8000-000000000032");
    static final UUID PRIOR_WO_2        = UUID.fromString("00000000-0000-7177-8000-000000000033");
    static final UUID PRIOR_WO_3        = UUID.fromString("00000000-0000-7177-8000-000000000034");

    // PII literals from grounding-seed.sql — must NOT appear in outbound payload
    static final List<String> PII_LITERALS = List.of(
            "James Thornton",
            "Gamma Meridian Ltd",
            "Gamma Meridian Limited",
            "james.thornton@gammameridian.example",
            "j.thornton@gammameridian.example",
            "+44 7700 900123",
            "07700 900456",
            "42 Oakfield Road",
            "M14 5AP"
    );

    @Autowired PromptAssembler promptAssembler;
    @Autowired RecordingAiGateway recordingGateway;
    @Autowired DatabaseCleaner dbCleaner;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUpSecurity() {
        // Set TECHNICIAN security context so @PreAuthorize passes
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(
                        TestJwtFactory.TECH_ONE_USER_ID.toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))));
    }

    // -------------------------------------------------------------------------
    // Grounding content
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Assemble prompt for WO with 3 prior closed WOs: basis lists all 3 contributing WOs")
    void assemble_basisContainsPriorWoIds() {
        AccessScope scope = techOneScope();
        CopilotResponse response = promptAssembler.assemble(WO_ASSET1_CURRENT, "What is the fault?", scope);

        assertThat(response.basis().assetId()).isEqualTo(ASSET1_ID);
        assertThat(response.basis().workOrderId()).isEqualTo(WO_ASSET1_CURRENT);
        assertThat(response.basis().contributingPriorWorkOrderIds())
                .containsExactlyInAnyOrder(PRIOR_WO_1, PRIOR_WO_2, PRIOR_WO_3);
    }

    @Test
    @DisplayName("System prompt includes asset model and fault code")
    void assemble_systemPromptContainsAssetAndFault() {
        AccessScope scope = techOneScope();
        promptAssembler.assemble(WO_ASSET1_CURRENT, "Diagnose please.", scope);

        AiCompletionRequest captured = recordingGateway.lastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.systemPrompt())
                .contains("TurboAir 5000")
                .contains("HVAC-NOISE")
                .contains("MECHANICAL");
    }

    // -------------------------------------------------------------------------
    // PII redaction — hard negative assertion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Hard negative: no seeded PII literal appears in outbound payload")
    void assemble_noPiiInOutboundPayload() {
        AccessScope scope = techOneScope();
        promptAssembler.assemble(WO_ASSET1_CURRENT, "What is the fault at Gamma Meridian HQ?", scope);

        AiCompletionRequest captured = recordingGateway.lastRequest();
        assertThat(captured).isNotNull();
        String payload = captured.systemPrompt() + " " + messagesText(captured);

        for (String pii : PII_LITERALS) {
            assertThat(payload)
                    .as("PII literal [%s] must not appear in outbound payload", pii)
                    .doesNotContainIgnoringCase(pii);
        }
    }

    // -------------------------------------------------------------------------
    // Sufficiency rules
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("WO with no prior history and no fault classification → INSUFFICIENT")
    void assemble_insufficientWhenNoPriorAndNoClassification() {
        AccessScope scope = techOneScope();
        // WO_ASSET2_CURRENT has no prior WOs and null faultCode/faultCategory
        assertThatThrownBy(() -> promptAssembler.assemble(WO_ASSET2_CURRENT, "Help?", scope))
                .isInstanceOf(GroundingUnavailableException.class)
                .hasMessageContaining("R3_THIN_HISTORY_NO_FAULT_CLASSIFICATION");
    }

    @Test
    @DisplayName("WO with 1 prior WO and only faultCode (no faultCategory) → SUFFICIENT via prior history")
    void assemble_sufficientWhenOnePriorWo() {
        AccessScope scope = techOneScope();
        // WO_ASSET3_CURRENT has 1 prior WO — sufficient under R3
        CopilotResponse response = promptAssembler.assemble(WO_ASSET3_CURRENT, "Boiler not heating.", scope);
        assertThat(response).isNotNull();
        assertThat(response.answer()).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Access control
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Tech-one cannot access work order assigned to tech-two")
    void assemble_accessDeniedForOtherTechnicianWo() {
        AccessScope scope = techOneScope();
        // WO_TECH2_ASSET1 is assigned to tech-two
        assertThatThrownBy(() -> promptAssembler.assemble(WO_TECH2_ASSET1, "Diagnose?", scope))
                .isInstanceOf(GroundingUnavailableException.class)
                .hasMessageContaining("WORK_ORDER_NOT_ACCESSIBLE");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AccessScope techOneScope() {
        return new AccessScope(
                TestJwtFactory.TECH_ONE_USER_ID,
                Set.of("TECHNICIAN"),
                TestJwtFactory.TECH_ONE_ID,
                Set.of());
    }

    private static String messagesText(AiCompletionRequest req) {
        return req.messages().stream()
                .map(AiCompletionRequest.AiMessage::content)
                .reduce("", (a, b) -> a + " " + b);
    }

    // -------------------------------------------------------------------------
    // Test configuration: recording AI gateway
    // -------------------------------------------------------------------------

    @TestConfiguration
    static class CopilotTestConfig {
        @Bean
        @Primary
        RecordingAiGateway recordingAiGateway() {
            return new RecordingAiGateway();
        }
    }

    static class RecordingAiGateway implements AiGatewayPort {
        private final FakeAiGatewayAdapter delegate = new FakeAiGatewayAdapter();
        private final List<AiCompletionRequest> captured = new ArrayList<>();

        @Override
        public AiCompletionResponse complete(AiCompletionRequest request) {
            captured.add(request);
            return delegate.complete(request);
        }

        @Override
        public void completeStreaming(AiCompletionRequest request, AiStreamCallback callback) {
            captured.add(request);
            delegate.completeStreaming(request, callback);
        }

        @Override
        public AiVisionResponse caption(AiVisionRequest request) {
            return delegate.caption(request);
        }

        AiCompletionRequest lastRequest() {
            return captured.isEmpty() ? null : captured.get(captured.size() - 1);
        }

        List<AiCompletionRequest> allRequests() {
            return Collections.unmodifiableList(captured);
        }
    }
}
