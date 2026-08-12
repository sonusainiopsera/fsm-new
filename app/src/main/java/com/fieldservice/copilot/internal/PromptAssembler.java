package com.fieldservice.copilot.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.copilot.api.CopilotResponse;
import com.fieldservice.platform.security.AccessScope;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The sole authorised constructor of copilot {@link AiCompletionRequest} objects.
 *
 * <p>No other class in the copilot module may call {@code new AiCompletionRequest(...)}.
 * This is enforced structurally: {@link GroundingContext} is package-private, so the only
 * code path from raw context to an outbound request runs through this class, which always
 * invokes the redactor.
 *
 * <p>Method-level security restricts access to TECHNICIAN and ADMIN roles.
 */
@Service
public class PromptAssembler {

    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final GroundingContextRetriever retriever;
    private final GroundingSufficiencyEvaluator evaluator;
    private final PiiRedactor redactor;
    private final AiGatewayPort gateway;

    public PromptAssembler(GroundingContextRetriever retriever,
                           GroundingSufficiencyEvaluator evaluator,
                           PiiRedactor redactor,
                           AiGatewayPort gateway) {
        this.retriever  = retriever;
        this.evaluator  = evaluator;
        this.redactor   = redactor;
        this.gateway    = gateway;
    }

    /**
     * Assembles a grounded, redacted AI prompt for the given work order and submits it
     * to the AI gateway.
     *
     * @param workOrderId  the work order to ground the response on
     * @param userQuestion the technician's free-text question (treated as untrusted data)
     * @param scope        the caller's access scope (applied as a row-level query predicate)
     * @return copilot response containing the model answer and grounding basis
     * @throws GroundingUnavailableException if the work order is not accessible or evidence is insufficient
     */
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'ADMIN')")
    public CopilotResponse assemble(UUID workOrderId, String userQuestion, AccessScope scope) {
        GroundingContext context = retriever.retrieve(workOrderId, scope);

        SufficiencyResult sufficiency = evaluator.evaluate(context);
        if (!sufficiency.isSufficient()) {
            throw new GroundingUnavailableException(sufficiency.reasonCode());
        }

        RedactedPrompt prompt = redactor.redact(context, userQuestion);

        AiCompletionRequest request = new AiCompletionRequest(
                scope.userId().toString(),
                prompt.systemPrompt(),
                List.of(new AiCompletionRequest.AiMessage(
                        AiCompletionRequest.AiMessage.Role.USER,
                        prompt.userPrompt())),
                DEFAULT_MAX_TOKENS);

        AiCompletionResponse response = gateway.complete(request);
        return new CopilotResponse(response.content(), prompt.basis());
    }
}
