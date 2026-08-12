package com.fieldservice.copilot.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.aiaudit.api.AiInteractionLogService;
import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamCallback;
import com.fieldservice.copilot.api.CopilotSseEvents;
import com.fieldservice.copilot.api.CopilotSseEvents.TokenEventData;
import com.fieldservice.copilot.api.CopilotSseEvents.TokenEventData.BasisEntry;
import com.fieldservice.copilot.api.GroundingBasis;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.exception.AiCapExceededException;
import com.fieldservice.platform.api.exception.AiUnavailableException;
import com.fieldservice.platform.api.exception.RateLimitedException;
import com.fieldservice.platform.security.AccessScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the copilot streaming pipeline:
 *
 * <ol>
 *   <li>Concurrent stream limit check (synchronous — 429 before emitter created).</li>
 *   <li>Grounding context retrieval (row-scoped via AccessScope predicate).</li>
 *   <li>Sufficiency evaluation — short-circuits to {@code no_grounded_basis} when INSUFFICIENT,
 *       with zero provider calls (safety invariant, AC-5).</li>
 *   <li>PII redaction and prompt assembly.</li>
 *   <li>Virtual-thread streaming via {@link AiGatewayPort#completeStreaming}.</li>
 *   <li>SSE token events with {@code advisory=true} and grounding basis on every chunk.</li>
 *   <li>Terminal event emission for every outcome class.</li>
 *   <li>Interaction log record for every outcome via {@link AiInteractionLogService}.</li>
 * </ol>
 *
 * <p>No personal data, internal state enums, dispatch scores, GPS coordinates, or
 * technician location appear in any SSE payload (AC-6, AC-11).
 */
@Service
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final GroundingContextRetriever retriever;
    private final GroundingSufficiencyEvaluator evaluator;
    private final PiiRedactor redactor;
    private final AiGatewayPort gateway;
    private final AiInteractionLogService logService;
    private final CopilotStreamProperties streamProperties;
    private final ObjectMapper objectMapper;

    /** Per-user active stream count — enforces the concurrent stream limit. */
    private final ConcurrentHashMap<UUID, AtomicInteger> activeStreams = new ConcurrentHashMap<>();

    public CopilotService(
            GroundingContextRetriever retriever,
            GroundingSufficiencyEvaluator evaluator,
            PiiRedactor redactor,
            AiGatewayPort gateway,
            AiInteractionLogService logService,
            CopilotStreamProperties streamProperties,
            ObjectMapper objectMapper) {
        this.retriever        = retriever;
        this.evaluator        = evaluator;
        this.redactor         = redactor;
        this.gateway          = gateway;
        this.logService       = logService;
        this.streamProperties = streamProperties;
        this.objectMapper     = objectMapper;
    }

    // ── Pre-flight concurrent stream limit ────────────────────────────────────

    /**
     * Checks whether the user has capacity for one more stream.
     * Called synchronously before the SseEmitter is created so a 429 response can be
     * returned rather than a terminal SSE event.
     *
     * @throws RateLimitedException if the user already holds the maximum number of streams
     */
    public void checkAndAcquireStreamSlot(UUID userId) {
        AtomicInteger counter = activeStreams.computeIfAbsent(userId, k -> new AtomicInteger(0));
        int current = counter.get();
        if (current >= streamProperties.maxConcurrentStreams()) {
            throw new RateLimitedException(60L);
        }
        counter.incrementAndGet();
    }

    /** Releases the stream slot acquired by {@link #checkAndAcquireStreamSlot}. */
    public void releaseStreamSlot(UUID userId) {
        AtomicInteger counter = activeStreams.get(userId);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    // ── Main stream orchestration ─────────────────────────────────────────────

    /**
     * Runs the complete copilot streaming pipeline on the calling thread.
     *
     * <p>Must be called from a virtual thread so the blocking gateway call does not
     * occupy a platform servlet thread. The controller is responsible for spawning
     * the virtual thread and returning the SseEmitter before this method is called.
     *
     * @param workOrderId  the work order to ground the response on
     * @param userQuestion the technician's free-text question (treated as untrusted data)
     * @param scope        the caller's row-level access scope
     * @param emitter      the SseEmitter to write events to
     */
    public void runStream(UUID workOrderId, String userQuestion, AccessScope scope, SseEmitter emitter) {
        UUID interactionId = UUID.randomUUID();
        Instant startTime  = Instant.now();

        AtomicBoolean terminated = new AtomicBoolean(false);
        AtomicInteger chunkIndex = new AtomicInteger(0);
        AtomicInteger tokenCount = new AtomicInteger(0);

        // ── Timeout: emit degraded terminal event and complete ─────────────────
        emitter.onTimeout(() -> {
            if (terminated.compareAndSet(false, true)) {
                quietSend(emitter,
                        "degraded",
                        CopilotSseEvents.DegradedEventData.of(interactionId,
                                "AI interaction time budget exceeded. Please try again."));
                emitter.complete();
                logInteraction(interactionId, scope.userId(), workOrderId,
                        AiInteractionLogService.Outcome.DEGRADED, startTime, tokenCount.get());
            }
        });

        // ── Client disconnect: cancel upstream call ────────────────────────────
        emitter.onCompletion(() -> terminated.set(true));
        emitter.onError(t -> terminated.set(true));

        // ── Step 1: load grounding context (row-scoped) ───────────────────────
        GroundingContext context;
        try {
            context = retriever.retrieve(workOrderId, scope);
        } catch (GroundingUnavailableException e) {
            // Work order not accessible — treat as not found (no existence disclosure)
            if (terminated.compareAndSet(false, true)) {
                quietSend(emitter,
                        "no_grounded_basis",
                        CopilotSseEvents.NoGroundedBasisData.of(interactionId, e.getReasonCode()));
                emitter.complete();
                logInteraction(interactionId, scope.userId(), workOrderId,
                        AiInteractionLogService.Outcome.REFUSED_NO_GROUNDING, startTime, 0);
            }
            return;
        }

        // ── Step 2: sufficiency check — MUST short-circuit before any provider call ──
        SufficiencyResult sufficiency = evaluator.evaluate(context);
        if (!sufficiency.isSufficient()) {
            // Safety invariant: no provider call made when grounding is INSUFFICIENT.
            if (terminated.compareAndSet(false, true)) {
                quietSend(emitter,
                        "no_grounded_basis",
                        CopilotSseEvents.NoGroundedBasisData.of(interactionId, sufficiency.reasonCode()));
                emitter.complete();
                logInteraction(interactionId, scope.userId(), workOrderId,
                        AiInteractionLogService.Outcome.REFUSED_NO_GROUNDING, startTime, 0);
            }
            return;
        }

        // ── Step 3: PII redaction and prompt assembly ─────────────────────────
        RedactedPrompt prompt = redactor.redact(context, userQuestion);
        GroundingBasis basis  = prompt.basis();
        List<BasisEntry> basisEntries = buildBasisEntries(basis);

        AiCompletionRequest request = new AiCompletionRequest(
                scope.userId().toString(),
                prompt.systemPrompt(),
                List.of(new AiCompletionRequest.AiMessage(
                        AiCompletionRequest.AiMessage.Role.USER, prompt.userPrompt())),
                DEFAULT_MAX_TOKENS);

        // ── Step 4: streaming (blocks virtual thread, not platform thread) ────
        AtomicBoolean providerCompletedNormally = new AtomicBoolean(false);

        try {
            gateway.completeStreaming(request, new AiStreamCallback() {
                @Override
                public void onToken(String chunk) {
                    if (terminated.get()) return;  // cancelled or timed out — stop sending
                    try {
                        emitter.send(SseEmitter.event()
                                .name("token")
                                .data(serialise(new TokenEventData(
                                        chunkIndex.getAndIncrement(),
                                        chunk,
                                        true,   // advisory is always true
                                        basisEntries))));
                        tokenCount.incrementAndGet();
                    } catch (IOException ignored) {
                        // Client disconnected — terminate loop via flag
                        terminated.set(true);
                    }
                }

                @Override
                public void onComplete() {
                    if (terminated.compareAndSet(false, true)) {
                        providerCompletedNormally.set(true);
                        quietSend(emitter,
                                "complete",
                                CopilotSseEvents.CompleteEventData.of(interactionId));
                        emitter.complete();
                        logInteraction(interactionId, scope.userId(), workOrderId,
                                AiInteractionLogService.Outcome.COMPLETED, startTime, tokenCount.get());
                    }
                }

                @Override
                public void onError(Throwable cause) {
                    log.warn("copilot_provider_stream_error interaction_id={}", interactionId, cause);
                    if (terminated.compareAndSet(false, true)) {
                        quietSend(emitter,
                                "degraded",
                                CopilotSseEvents.DegradedEventData.of(interactionId,
                                        "AI assistance temporarily unavailable."));
                        emitter.complete();
                        logInteraction(interactionId, scope.userId(), workOrderId,
                                AiInteractionLogService.Outcome.DEGRADED, startTime, tokenCount.get());
                    }
                }
            });

            // Provider stream ended without signaling complete or error — treat as degraded
            if (!providerCompletedNormally.get() && terminated.compareAndSet(false, true)) {
                quietSend(emitter,
                        "degraded",
                        CopilotSseEvents.DegradedEventData.of(interactionId,
                                "AI provider ended stream without a completion signal."));
                emitter.complete();
                logInteraction(interactionId, scope.userId(), workOrderId,
                        AiInteractionLogService.Outcome.DEGRADED, startTime, tokenCount.get());
            }

        } catch (AiCapExceededException e) {
            log.info("copilot_cap_exceeded user_id={} interaction_id={}", scope.userId(), interactionId);
            if (terminated.compareAndSet(false, true)) {
                quietSend(emitter,
                        "degraded",
                        CopilotSseEvents.DegradedEventData.of(interactionId,
                                "Daily AI interaction limit reached. Please try again tomorrow."));
                emitter.complete();
                logInteraction(interactionId, scope.userId(), workOrderId,
                        AiInteractionLogService.Outcome.DEGRADED, startTime, tokenCount.get());
            }
        } catch (AiUnavailableException e) {
            log.warn("copilot_provider_unavailable interaction_id={}", interactionId, e);
            if (terminated.compareAndSet(false, true)) {
                quietSend(emitter,
                        "degraded",
                        CopilotSseEvents.DegradedEventData.of(interactionId,
                                "AI assistance is temporarily unavailable."));
                emitter.complete();
                logInteraction(interactionId, scope.userId(), workOrderId,
                        AiInteractionLogService.Outcome.DEGRADED, startTime, tokenCount.get());
            }
        } catch (Exception e) {
            log.error("copilot_unexpected_error interaction_id={}", interactionId, e);
            if (terminated.compareAndSet(false, true)) {
                quietSend(emitter,
                        "degraded",
                        CopilotSseEvents.DegradedEventData.of(interactionId,
                                "An unexpected error occurred. Please try again."));
                emitter.complete();
                logInteraction(interactionId, scope.userId(), workOrderId,
                        AiInteractionLogService.Outcome.DEGRADED, startTime, tokenCount.get());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<BasisEntry> buildBasisEntries(GroundingBasis basis) {
        List<BasisEntry> entries = new java.util.ArrayList<>();
        if (basis.assetId() != null) {
            entries.add(new BasisEntry("asset", basis.assetId()));
        }
        if (basis.workOrderId() != null) {
            entries.add(new BasisEntry("work_order", basis.workOrderId()));
        }
        if (basis.contributingPriorWorkOrderIds() != null) {
            for (UUID id : basis.contributingPriorWorkOrderIds()) {
                entries.add(new BasisEntry("prior_work_order", id));
            }
        }
        return List.copyOf(entries);
    }

    /** Serialises the event data object to JSON string; returns error placeholder on failure. */
    private String serialise(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("copilot_event_serialise_error", e);
            return "{\"error\":\"serialisation_failed\"}";
        }
    }

    /** Sends an event; swallows IOException (client already disconnected). */
    private void quietSend(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(serialise(data)));
        } catch (IOException ignored) {
            // Client disconnected before terminal event could be sent — acceptable.
        }
    }

    private void logInteraction(UUID interactionId, UUID userId, UUID workOrderId,
                                 AiInteractionLogService.Outcome outcome,
                                 Instant startTime, int tokenCount) {
        long latencyMs = Duration.between(startTime, Instant.now()).toMillis();
        logService.record(AiInteractionLogService.LogEntry.builder()
                .interactionId(interactionId)
                .interactionType(AiInteractionLogService.InteractionType.COPILOT_QUESTION)
                .actorUserId(userId)
                .workOrderId(workOrderId)
                .outcome(outcome)
                .latencyMs(latencyMs)
                .completionTokens(tokenCount)
                .redactionSummaryJson("{}")
                .redactorVersion("1.0")
                .build());
    }
}
