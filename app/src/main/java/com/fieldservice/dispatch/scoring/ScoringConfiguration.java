package com.fieldservice.dispatch.scoring;

import com.fieldservice.dispatch.scoring.factors.CompetencyFitFactor;
import com.fieldservice.dispatch.scoring.factors.PartsAvailabilityFactor;
import com.fieldservice.dispatch.scoring.factors.TravelEfficiencyFactor;
import com.fieldservice.dispatch.scoring.factors.WorkloadFairnessFactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring wiring for the framework-free scoring engine and its factor implementations.
 *
 * <p>{@link ScoringEngine} is constructed here so it carries no Spring annotations
 * itself, satisfying the testability and dependency-injection standard from the WO.
 */
@Configuration
public class ScoringConfiguration {

    @Value("${dispatch.scoring.parts.nearby-collectable-discount:"
            + PartsAvailabilityFactor.DEFAULT_NEARBY_COLLECTABLE_DISCOUNT + "}")
    private double nearbyCollectableDiscount;

    @Bean
    public ScoringEngine scoringEngine() {
        return new ScoringEngine(List.of(
                new CompetencyFitFactor(),
                new TravelEfficiencyFactor(),
                new WorkloadFairnessFactor(),
                new PartsAvailabilityFactor(nearbyCollectableDiscount)
        ));
    }
}
