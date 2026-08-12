package com.fieldservice.dispatch.api;

import com.fieldservice.dispatch.eligibility.CandidateReadRepository;
import com.fieldservice.dispatch.eligibility.EligibilityFilter;
import com.fieldservice.dispatch.eligibility.EligibilityMetrics;
import com.fieldservice.dispatch.eligibility.TechnicianCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Public dispatch module API for technician eligibility evaluation.
 *
 * <p>This is the only entry point other modules may use — all internal types
 * in {@code dispatch.eligibility} are package-private and unreachable from outside.
 *
 * <h3>Fail-closed</h3>
 * Any exception during candidate loading propagates to the caller as a runtime
 * exception rather than returning an unfiltered list.
 *
 * <h3>Observability</h3>
 * Structured log on exclusion aggregates only (counts by reason) — no per-technician
 * PII is written to the log, per the PII masking policy.
 */
@Service
@Transactional(readOnly = true)
public class EligibilityService {

    private static final Logger log = LoggerFactory.getLogger(EligibilityService.class);

    private final CandidateReadRepository repository;
    private final EligibilityFilter       filter;
    private final EligibilityMetrics      metrics;

    public EligibilityService(CandidateReadRepository repository,
                              EligibilityMetrics metrics,
                              Clock clock) {
        this.repository = repository;
        this.filter     = new EligibilityFilter(clock);
        this.metrics    = metrics;
    }

    /**
     * Returns the eligible technician set for the given work order requirements.
     *
     * @param requirements work order requirements snapshot
     * @return eligibility result — eligible IDs, excluded IDs with reasons, reachUnknown flag
     * @throws org.springframework.dao.DataAccessException if candidate data cannot be loaded
     */
    public EligibilityResult evaluate(WorkOrderRequirements requirements) {
        return metrics.durationTimer().record(() -> {
            List<TechnicianCandidate> candidates = repository.loadCandidates(
                    requirements.serviceWindowStart(), requirements.serviceWindowEnd());
            EligibilityResult result = filter.filter(candidates, requirements);
            metrics.recordResult(result);
            logAggregate(result);
            return result;
        });
    }

    private static void logAggregate(EligibilityResult result) {
        if (result.excluded().isEmpty()) {
            log.debug("dispatch.eligibility eligible={} excluded=0 reachUnknown={}",
                    result.eligible().size(), result.reachUnknown());
            return;
        }
        Map<ExclusionReason, Long> counts = result.excluded().stream()
                .collect(Collectors.groupingBy(ExcludedCandidate::reason, Collectors.counting()));
        log.info("dispatch.eligibility eligible={} excluded={} byReason={} reachUnknown={}",
                result.eligible().size(), result.excluded().size(), counts, result.reachUnknown());
    }
}
