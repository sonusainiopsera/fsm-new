package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.ExclusionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Micrometer instrumentation for the dispatch eligibility gate.
 *
 * <p>Meters published:
 * <ul>
 *   <li>{@code dispatch.eligibility.duration} timer — wall time per evaluate() call</li>
 *   <li>{@code dispatch.eligibility.candidates} distribution summary — pool size per call</li>
 *   <li>{@code dispatch.eligibility.excluded} counter tagged by {@code reason} — exclusion counts</li>
 * </ul>
 *
 * <p>Structured log messages at INFO level are emitted for exclusion aggregates only;
 * per-technician data is never logged to comply with the PII masking policy.
 */
@Component
class EligibilityMetrics {

    static final String TIMER_NAME    = "dispatch.eligibility.duration";
    static final String SUMMARY_NAME  = "dispatch.eligibility.candidates";
    static final String COUNTER_NAME  = "dispatch.eligibility.excluded";
    static final String TAG_REASON    = "reason";

    private final Timer durationTimer;
    private final DistributionSummary candidateSummary;
    private final Map<ExclusionReason, Counter> exclusionCounters;

    EligibilityMetrics(MeterRegistry registry) {
        this.durationTimer = Timer.builder(TIMER_NAME)
                .description("Wall time for one eligibility evaluation pass")
                .register(registry);

        this.candidateSummary = DistributionSummary.builder(SUMMARY_NAME)
                .description("Number of technician candidates evaluated per request")
                .baseUnit("candidates")
                .register(registry);

        this.exclusionCounters = new EnumMap<>(ExclusionReason.class);
        for (ExclusionReason reason : ExclusionReason.values()) {
            Counter counter = Counter.builder(COUNTER_NAME)
                    .description("Number of technicians excluded from dispatch recommendations")
                    .tag(TAG_REASON, reason.name())
                    .register(registry);
            exclusionCounters.put(reason, counter);
        }
    }

    Timer durationTimer()                                { return durationTimer; }
    DistributionSummary candidateSummary()               { return candidateSummary; }
    Counter exclusionCounter(ExclusionReason reason)     { return exclusionCounters.get(reason); }
}
