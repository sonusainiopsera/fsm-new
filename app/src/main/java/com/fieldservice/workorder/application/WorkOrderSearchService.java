package com.fieldservice.workorder.application;

import com.fieldservice.workorder.domain.WorkOrder;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds a JPA {@link Specification} from a {@link WorkOrderSearchCriteria}.
 *
 * <p>Each non-null criterion is ANDed together. The resulting spec is always further
 * ANDed with the mandatory AccessScope predicate by {@code ScopedQueryExecutor} so
 * out-of-scope rows are never returned.
 */
@Service
public class WorkOrderSearchService {

    /**
     * Converts search criteria into a composable JPA specification.
     * Returns a conjunction (permit-all) when all criteria fields are null.
     */
    public Specification<WorkOrder> toSpecification(WorkOrderSearchCriteria criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return Specification.where(null); // no additional filter
        }

        Specification<WorkOrder> spec = Specification.where(null);

        if (criteria.states() != null && !criteria.states().isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("state").in(criteria.states()));
        }

        if (criteria.priority() != null && !criteria.priority().isBlank()) {
            String p = criteria.priority().toUpperCase();
            spec = spec.and((root, q, cb) -> cb.equal(root.get("priority"), p));
        }

        if (criteria.assignedTechnicianId() != null) {
            UUID techId = criteria.assignedTechnicianId();
            spec = spec.and((root, q, cb) ->
                    cb.equal(root.get("assignedTechnicianId"), techId));
        }

        if (criteria.siteId() != null) {
            UUID sid = criteria.siteId();
            spec = spec.and((root, q, cb) ->
                    cb.equal(root.join("site", JoinType.INNER).get("id"), sid));
        }

        if (criteria.customerId() != null) {
            UUID cid = criteria.customerId();
            spec = spec.and((root, q, cb) ->
                    cb.equal(root.join("site", JoinType.INNER).get("customerId"), cid));
        }

        if (criteria.createdFrom() != null) {
            Instant from = criteria.createdFrom();
            spec = spec.and((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }

        if (criteria.createdTo() != null) {
            Instant to = criteria.createdTo();
            spec = spec.and((root, q, cb) ->
                    cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }

        if (criteria.deadlineFrom() != null) {
            Instant from = criteria.deadlineFrom();
            spec = spec.and((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("resolutionDeadline"), from));
        }

        if (criteria.deadlineTo() != null) {
            Instant to = criteria.deadlineTo();
            spec = spec.and((root, q, cb) ->
                    cb.lessThanOrEqualTo(root.get("resolutionDeadline"), to));
        }

        if (criteria.atRisk() != null) {
            boolean risk = criteria.atRisk();
            spec = spec.and((root, q, cb) -> cb.equal(root.get("atRisk"), risk));
        }

        return spec;
    }
}
