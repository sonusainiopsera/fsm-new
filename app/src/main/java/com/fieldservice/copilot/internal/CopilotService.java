package com.fieldservice.copilot.internal;

import com.fieldservice.aigateway.api.AiCapExceededException;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.aigateway.api.AiUnavailableException;
import com.fieldservice.copilot.api.AiInteractionLogService;
import com.fieldservice.copilot.api.CopilotInteractionOutcome;
import com.fieldservice.copilot.api.CopilotSseEvents;
import com.fieldservice.copilot.api.GroundingBasis;
import com.fieldservice.copilot.api.GroundingUnavailableException;
import com.fieldservice.copilot.api.RedactedPrompt;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Orchestrates copilot streaming: grounding → sufficiency → prompt assembly → provider
 * streaming → interaction logging. Runs entirely on the calling (virtual) thread.
 *
 * <p>Safety invariant: INSUFFICIENT grounding short-circuits before any provider call.
 * A dedicated budget thread enforces the hard 10-second timeout.
 */
@Service
@EnableConfigurationProperties(CopilotStreamProperties.class)
public class CopilotService {

    private final PromptAssembler promptAssembler;
    private final AiGatewayPort aiGateway;
    private final AiInteractionLogService interactionLog;
    private final CopilotStreamProperties streamProps;

    public CopilotService(
            PromptAssembler promptAssembler,
            AiGatewayPort aiGateway,
            AiInteractionLogService interactionLog,
            CopilotStreamProperties streamProps) {
        this.promptAssembler = promptAssembler;
        this.aiGateway = aiGateway;
        this.interactionLog = interactionLog;
        this.streamProps = streamProps;
    }

    /**
     * Executes the full copilot stream for a work-order context question.
     * Intended to be called from a virtual thread so the blocking provider call does not
     * consume a platform thread.
     *
     * @param interactionId stable ID for this interaction
     * @param workOrderId   target work order (row-scope enforced via PromptAssembler)
     * @param userQuery     technician's question
     * @param userId        authenticated user identifier (opaque UUID string)
     * @param emitter       SSE emitter to write events to
     */
    public void executeStream(
            UUID interactionId,
            UUID workOrderId,
            String userQuery,
            String userId,
            SseEmitter emitter) {

        Instant start = Instant.now();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger tokenCount = new AtomicInteger(0);

        // ── Budget timer on a virtual thread ─────────────────────────────────
        Thread budgetThread = Thread.ofVirtual()
                .name("copilot-budget-" + interactionId)
                .start(() -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(streamProps.getBudgetSeconds()));
                        if (finished.compareAndSet(false, true)) {
                            safeSend(emitter, "degraded", new CopilotSseEvents.DegradedEvent(interactionId));
                            emitter.complete();
                            log(interactionId, userId, workOrderId,
                                    CopilotInteractionOutcome.DEGRADED, start, tokenCount.get(), "budget exceeded");
                        }
                    } catch (InterruptedException ignored) {
                        // Stream completed normally — timer cancelled
                    }
                });

        try {
            // ── Step 1: assemble prompt (includes grounding, sufficiency, redaction, row-scope)
            RedactedPrompt redacted;
            try {
                redacted = promptAssembler.assemble(workOrderId, userQuery);
            } catch (GroundingUnavailableException e) {
                cancelBudget(budgetThread);
                if (finished.compareAndSet(false, true)) {
                    safeSend(emitter, "no_grounded_basis", new CopilotSseEvents.NoGroundedBasisEvent(
                            interactionId,
                            CopilotSseEvents.NoGroundedBasisEvent.CODE,
                            e.getReasonCode().name(),
                            "No grounded basis exists for this query."));
                    emitter.complete();
                    log(interactionId, userId, workOrderId,
                            CopilotInteractionOutcome.REFUSED_NO_GROUNDING, start, 0, "");
                }
                return;
            }

            List<CopilotSseEvents.TokenEvent.BasisRef> basisRefs = buildBasisRefs(redacted.groundingBasis());
            String redactionSummary = redacted.redactionReport().totalSubstitutions() + " substitutions";

            // ── Step 2: stream tokens from provider ──────────────────────────
            Stream<AiStreamChunk> chunks = aiGateway.completeStreaming(redacted.request());

            int[] idx = {0};
            chunks.forEach(chunk -> {
                if (finished.get()) return;
                safeSend(emitter, "token", new CopilotSseEvents.TokenEvent(
                        idx[0]++,
                        chunk.delta(),
                        true,
                        basisRefs));
                tokenCount.incrementAndGet();
            });

            // ── Step 3: complete normally ─────────────────────────────────────
            cancelBudget(budgetThread);
            if (finished.compareAndSet(false, true)) {
                safeSend(emitter, "complete", new CopilotSseEvents.CompleteEvent(interactionId));
                emitter.complete();
                log(interactionId, userId, workOrderId,
                        CopilotInteractionOutcome.COMPLETED, start, tokenCount.get(), redactionSummary);
            }

        } catch (AiCapExceededException | AiUnavailableException e) {
            cancelBudget(budgetThread);
            if (finished.compareAndSet(false, true)) {
                safeSend(emitter, "degraded", new CopilotSseEvents.DegradedEvent(interactionId));
                emitter.complete();
                log(interactionId, userId, workOrderId,
                        CopilotInteractionOutcome.DEGRADED, start, tokenCount.get(), "");
            }
        } catch (StreamAbortedException e) {
            // Client disconnected — upstream already completed
            cancelBudget(budgetThread);
            if (finished.compareAndSet(false, true)) {
                log(interactionId, userId, workOrderId,
                        CopilotInteractionOutcome.CANCELLED, start, tokenCount.get(), "");
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static List<CopilotSseEvents.TokenEvent.BasisRef> buildBasisRefs(GroundingBasis basis) {
        var refs = new ArrayList<CopilotSseEvents.TokenEvent.BasisRef>();
        if (basis.assetId() != null) {
            refs.add(new CopilotSseEvents.TokenEvent.BasisRef("ASSET", basis.assetId().toString()));
        }
        for (UUID woId : basis.contributingWorkOrderIds()) {
            refs.add(new CopilotSseEvents.TokenEvent.BasisRef("WORK_ORDER", woId.toString()));
        }
        return List.copyOf(refs);
    }

    private void safeSend(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            throw new StreamAbortedException(e);
        }
    }

    private static void cancelBudget(Thread budgetThread) {
        budgetThread.interrupt();
    }

    private void log(UUID interactionId, String userId, UUID workOrderId,
                     CopilotInteractionOutcome outcome, Instant start,
                     int tokens, String redactionSummary) {
        try {
            UUID actorId = null;
            try {
                actorId = UUID.fromString(userId);
            } catch (Exception ignored) {}
            interactionLog.record(new AiInteractionLogService.InteractionRecord(
                    interactionId, actorId, workOrderId, outcome,
                    Duration.between(start, Instant.now()), tokens, redactionSummary));
        } catch (Exception ignored) {
            // Logging must never propagate to the stream path
        }
    }

    /** Thrown when the SSE emitter rejects a send (client disconnected). */
    static class StreamAbortedException extends RuntimeException {
        StreamAbortedException(Throwable cause) { super(cause); }
    }
}
