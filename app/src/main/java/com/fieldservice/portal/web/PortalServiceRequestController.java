package com.fieldservice.portal.web;

import com.fieldservice.portal.service.PortalServiceRequestService;
import com.fieldservice.portal.web.dto.CreateServiceRequestRequest;
import com.fieldservice.portal.web.dto.ServiceRequestResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Portal endpoint for submitting field service requests.
 *
 * <p>Security: {@code CUSTOMER} role is enforced both here and in the
 * delegated service ({@link PortalServiceRequestService}).
 * The {@link PortalExceptionAdvice} scoped to this package handles
 * {@link com.fieldservice.portal.access.ScopeUnavailableException} → 404,
 * preventing disclosure of whether a resource exists in a different account's scope.
 */
@RestController
@RequestMapping("/api/v1/portal/service-requests")
public class PortalServiceRequestController {

    private final PortalServiceRequestService service;

    public PortalServiceRequestController(PortalServiceRequestService service) {
        this.service = service;
    }

    /**
     * Submit a new portal service request.
     *
     * <p>Returns 201 Created with a {@code Location} header pointing to the
     * work order resource at {@code /api/v1/work-orders/{workOrderId}}.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ServiceRequestResponse> submit(
            @Valid @RequestBody CreateServiceRequestRequest request) {

        ServiceRequestResponse response = service.submit(request);

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/work-orders/{id}")
                .buildAndExpand(response.workOrderId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }
}
