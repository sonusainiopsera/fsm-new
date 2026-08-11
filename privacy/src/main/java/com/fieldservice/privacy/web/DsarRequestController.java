package com.fieldservice.privacy.web;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.privacy.api.CreateDsarRequest;
import com.fieldservice.privacy.api.DsarAdminPort;
import com.fieldservice.privacy.api.DsarRequestView;
import com.fieldservice.privacy.api.DsarTransitionRequest;
import com.fieldservice.privacy.api.ExportResponse;
import com.fieldservice.privacy.api.FulfillmentMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoints for the DSAR admin workflow.
 *
 * <p>All endpoints require {@code PRIVACY_ADMIN} or {@code ADMIN} role;
 * method security is enforced in the service layer via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/privacy/dsar-requests")
public class DsarRequestController {

    private final DsarAdminPort adminPort;

    public DsarRequestController(DsarAdminPort adminPort) {
        this.adminPort = adminPort;
    }

    /**
     * Submit a new data subject access request.
     * Returns 201 Created with the request view including state=RECEIVED and dueAt.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DsarRequestView createRequest(@Valid @RequestBody CreateDsarRequest body) {
        return adminPort.createRequest(body);
    }

    /**
     * Apply a lifecycle event (ACKNOWLEDGE, VERIFY, START_PROCESSING, FULFILL, REJECT, WITHDRAW).
     * Returns 200 with the updated request view.
     * Returns 409 for illegal transitions; 422 for guard refusals.
     */
    @PostMapping("/{id}/transitions")
    public DsarRequestView applyTransition(@PathVariable UUID id,
                                            @Valid @RequestBody DsarTransitionRequest body) {
        return adminPort.applyTransition(id, body);
    }

    /**
     * List the DSAR queue, paginated and ordered by dueAt ascending.
     * Optional {@code state} query parameter filters by state.
     * Maximum page size is 50.
     */
    @GetMapping
    public PagedResponse<DsarRequestView> listRequests(
            PageQuery pageQuery,
            @RequestParam(required = false) @Nullable String state,
            HttpServletRequest request) {
        return adminPort.listRequests(pageQuery, state, request);
    }

    /** Returns a single DSAR request by ID. */
    @GetMapping("/{id}")
    public DsarRequestView getRequest(@PathVariable UUID id) {
        return adminPort.getRequest(id);
    }

    /**
     * Returns the export artifact and a short-lived download URL (max 300s).
     * Returns 404 if no artifact exists; 422 if the request is not ready for download.
     */
    @GetMapping("/{id}/export")
    public ExportResponse getExport(@PathVariable UUID id) {
        return adminPort.getExport(id);
    }

    /**
     * Returns O7 guardrail fulfilment metrics:
     * count fulfilled in-time vs total closed, plus open requests by remaining days.
     */
    @GetMapping("/metrics/fulfillment")
    public FulfillmentMetrics getFulfillmentMetrics() {
        return adminPort.getFulfillmentMetrics();
    }
}
