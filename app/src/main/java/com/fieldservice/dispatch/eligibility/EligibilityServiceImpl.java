package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Spring-managed implementation of {@link EligibilityService}.
 *
 * <p>Orchestrates data loading, pure filtering, and Micrometer instrumentation.
 * The read transaction is read-only to avoid write-lock contention on busy pools.
 */
@Service
class EligibilityServiceImpl implements EligibilityService {

    private final CandidateReadRepository repository;
    private final EligibilityFilter        filter;
    private final EligibilityMetrics       metrics;
    private final DispatchProperties       properties;

    EligibilityServiceImpl(CandidateReadRepository repository,
                           EligibilityMetrics metrics,
                           DispatchProperties properties,
                           Clock clock) {
        this.repository = repository;
        this.filter     = new EligibilityFilter(clock);
        this.metrics    = metrics;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public EligibilityResult evaluate(WorkOrderRequirements requirements) {
        return metrics.durationTimer().record(() -> doEvaluate(requirements));
    }

    private EligibilityResult doEvaluate(WorkOrderRequirements requirements) {
        List<TechnicianCandidate> candidates = repository.loadCandidates(
                properties.getCandidatePageSize(),
                requirements.serviceWindowStart(),
                requirements.serviceWindowEnd()
        );

        metrics.candidateSummary().record(candidates.size());

        EligibilityResult result = filter.filter(candidates, requirements);

        for (ExcludedCandidate exc : result.excluded()) {
            metrics.exclusionCounter(exc.reason()).increment();
        }

        return result;
    }
}
