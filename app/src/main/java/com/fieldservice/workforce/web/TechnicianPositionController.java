package com.fieldservice.workforce.web;

import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.workforce.internal.PositionReportingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts position reports from the authenticated technician.
 *
 * <p>The technician identity is resolved from the JWT subject only —
 * the client cannot supply a technician id.
 */
@RestController
@RequestMapping("/api/v1/technicians/me")
@PreAuthorize("hasRole('TECHNICIAN')")
public class TechnicianPositionController {

    private final PositionReportingService positionReportingService;
    private final RequestScopedAccessScope accessScope;

    public TechnicianPositionController(
            PositionReportingService positionReportingService,
            RequestScopedAccessScope accessScope) {
        this.positionReportingService = positionReportingService;
        this.accessScope              = accessScope;
    }

    @PostMapping("/position")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void reportPosition(@Valid @RequestBody PositionRequest request) {
        positionReportingService.reportPosition(accessScope.get().userId(), request);
    }
}
