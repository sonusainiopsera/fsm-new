package com.fieldservice.privacy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service contract for DSAR request lifecycle management.
 *
 * <p>All mutating methods write an Envers revision and an outbox event in the same
 * transaction as the state change, so no state transition can exist without its audit record.
 */
public interface DsarRequestService {

    /**
     * Creates a new DSAR request in state RECEIVED with a 30-day due date.
     *
     * @param requestType  the type of right being exercised
     * @param subjectType  stable subject-type string (e.g. "APP_USER")
     * @param subjectId    UUID of the subject's primary record
     * @param notes        optional intake notes
     * @param actor        authenticated user who submitted the request
     * @return the new request view
     */
    DsarRequestView createRequest(DsarRequestType requestType,
                                   String subjectType,
                                   UUID subjectId,
                                   String notes,
                                   String actor);

    /**
     * Applies a lifecycle event to the request.
     *
     * @param id                 request id
     * @param event              the lifecycle event to apply
     * @param verificationMethod for RECORD_VERIFICATION / VERIFY_DIRECT; otherwise null
     * @param note               optional note (e.g. rejection reason)
     * @param expectedVersion    optimistic-lock version
     * @param actor              authenticated actor
     * @throws com.fieldservice.platform.api.exception.NotFoundException    if request not found
     * @throws com.fieldservice.platform.api.exception.ConflictException    if version mismatch
     * @throws DsarIllegalTransitionException if the event is not legal from the current state
     * @throws DsarGuardRefusalException      if a guard rejects the transition
     */
    DsarRequestView applyTransition(UUID id,
                                     DsarEvent event,
                                     String verificationMethod,
                                     String note,
                                     int expectedVersion,
                                     String actor);

    /**
     * Returns a paginated list of DSAR requests, optionally filtered by state.
     */
    Page<DsarRequestView> listRequests(DsarState stateFilter, Pageable pageable);

    /**
     * Returns a single DSAR request by id.
     *
     * @throws com.fieldservice.platform.api.exception.NotFoundException if not found
     */
    DsarRequestView getRequest(UUID id);

    /**
     * Returns the export artifact view for a FULFILLED request, including a
     * short-lived download token valid for at most {@code expiresInSeconds}.
     *
     * @throws com.fieldservice.platform.api.exception.NotFoundException if not found
     * @throws ExportNotReadyException if the request is not FULFILLED
     */
    ExportArtifactView getExport(UUID requestId, String actor);

    /**
     * Computes the fulfilment metric for the O7 guardrail.
     *
     * @return snapshot covering fulfilled-on-time rate and open requests by remaining days
     */
    FulfilmentMetric getFulfilmentMetric();

    /** View of the O7 fulfilment metric. */
    record FulfilmentMetric(
            long totalClosed,
            long fulfilledOnTime,
            double onTimeRate,
            long openCount,
            long openAtRisk
    ) {}

    /** Thrown when a lifecycle event is not legal from the current state. */
    class DsarIllegalTransitionException extends RuntimeException {
        public DsarIllegalTransitionException(String message) {
            super(message);
        }
    }

    /** Thrown when a guard rejects a transition (422). */
    class DsarGuardRefusalException extends RuntimeException {
        private final String code;
        public DsarGuardRefusalException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }

    /** Thrown when export is requested for a non-FULFILLED request. */
    class ExportNotReadyException extends RuntimeException {
        public ExportNotReadyException(String message) {
            super(message);
        }
    }
}
