package com.fieldservice.dispatch.scoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the framework-free scoring engine and its factor strategy implementations.
 *
 * <p>All factor beans and the engine are plain POJOs. Spring manages their lifecycle
 * here so they remain independently testable without a Spring context.
 */
@Configuration
public class ScoringConfiguration {

    @Bean
    public CompetencyFitFactor competencyFitFactor() {
        return new CompetencyFitFactor();
    }

    @Bean
    public TravelEfficiencyFactor travelEfficiencyFactor() {
        return new TravelEfficiencyFactor();
    }

    @Bean
    public PartsAvailabilityFactor partsAvailabilityFactor() {
        return new PartsAvailabilityFactor();
    }

    /**
     * WorkloadFairnessFactor requires the exponent from configuration.
     * The exponent is supplied at startup by reading from {@link ScoringWeightsLoader};
     * the bean is recreated when weights are reloaded if the exponent changes.
     *
     * <p>Because the exponent must be > 1.0 (enforced in the constructor), a
     * misconfigured table will fail at context startup rather than silently at
     * ranking time.
     */
    @Bean
    public WorkloadFairnessFactor workloadFairnessFactor(ScoringWeightsLoader weightsLoader) {
        return new WorkloadFairnessFactor(weightsLoader.get().workloadExponent());
    }

    @Bean
    public ScoringEngine scoringEngine(CompetencyFitFactor competencyFitFactor,
                                        TravelEfficiencyFactor travelEfficiencyFactor,
                                        WorkloadFairnessFactor workloadFairnessFactor,
                                        PartsAvailabilityFactor partsAvailabilityFactor) {
        return new ScoringEngine(List.of(
                competencyFitFactor,
                travelEfficiencyFactor,
                workloadFairnessFactor,
                partsAvailabilityFactor));
    }
}
