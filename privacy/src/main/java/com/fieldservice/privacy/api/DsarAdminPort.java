package com.fieldservice.privacy.api;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Public service contract for DSAR (Data Subject Access Request) administration.
 *
 * <p>All methods require {@code PRIVACY_ADMIN} or {@code ADMIN} role via
 * {@code @PreAuthorize} in the implementation. Non-privacy roles receive 403 with
 * no existence disclosure of the request ID.
 */
public interface DsarAdminPort {

    /**
     * Submits a new data subject request. The initial state is {@code RECEIVED} and
     * {@code dueAt} is computed as {@code submittedAt + 30 days}.
     */
    DsarRequestView createRequest(CreateDsarRequest request);

    /**
     * Applies a lifecycle event to the request.
     *
     * @throws com.fieldservice.platform.exception.IllegalTransitionException  (409) if the
     *         event is not legal from the current state
     * @throws com.fieldservice.platform.exception.BusinessGuardException      (422) if a
     *         guard refuses the transition (e.g. verificationMethod missing on VERIFY)
     */
    DsarRequestView applyTransition(UUID id, DsarTransitionRequest transitionRequest);

    /**
     * Returns a paginated view of the DSAR queue ordered by {@code dueAt} ascending
     * with a tie-break on {@code id}. Maximum page size is 50.
     *
     * @param state optional state filter; {@code null} returns all states
     */
    PagedResponse<DsarRequestView> listRequests(PageQuery pageQuery,
                                                @Nullable String state,
                                                HttpServletRequest servletRequest);

    /** Returns a single request by ID, or throws {@code NotFoundException} (404). */
    DsarRequestView getRequest(UUID id);

    /**
     * Returns the export artifact for a fulfilled request plus a short-lived download URL.
     * Throws {@code NotFoundException} (404) if no artifact exists, and
     * {@code BusinessGuardException} (422) if the request is not in a terminal state.
     */
    ExportResponse getExport(UUID id);

    /** Returns O7 guardrail metrics for the DSAR queue. */
    FulfillmentMetrics getFulfillmentMetrics();
}
