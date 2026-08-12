package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.ExclusionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Micrometer collaborator for the eligibility gate.
 *
 * <p>Keeps EligibilityFilter framework-free by isolating all meter interactions here.
 *
 * <h3>Meters</h3>
 * <ul>
 *   <li>{@code dispatch.eligibility.duration} — timer around the full filter pass</li>
 *   <li>{@code dispatch.eligibility.candidates} — counter of candidates evaluated</li>
 *   <li>{@code dispatch.eligibility.excluded} — counter per ExclusionReason tag</li>
 * </ul>
 */
@Component
class EligibilityMetrics {

    static final String TIMER_NAME     = "dispatch.eligibility.duration";
    static final String CANDIDATES     = "dispatch.eligibility.candidates";
    static final String EXCLUDED       = "dispatch.eligibility.excluded";

    private final Timer durationTimer;
    private final Counter candidatesCounter;
    private final Map<ExclusionReason, Counter> excludedCounters;

    EligibilityMetrics(MeterRegistry registry) {
        this.durationTimer = Timer.builder(TIMER_NAME)
                .description("Time taken to complete one eligibility evaluation pass")
                .register(registry);

        this.candidatesCounter = Counter.builder(CANDIDATES)
                .description("Total technician candidates evaluated")
                .register(registry);

        this.excludedCounters = new EnumMap<>(ExclusionReason.class);
        for (ExclusionReason reason : ExclusionReason.values()) {
            excludedCounters.put(reason, Counter.builder(EXCLUDED)
                    .description("Excluded technicians tagged by exclusion reason")
                    .tag("reason", reason.name())
                    .register(registry));
        }
    }

    Timer durationTimer() { return durationTimer; }

    void recordResult(EligibilityResult result) {
        int total = result.eligible().size() + result.excluded().size();
        candidatesCounter.increment(total);
        for (var excl : result.excluded()) {
            excludedCounters.get(excl.reason()).increment();
        }
    }
}
