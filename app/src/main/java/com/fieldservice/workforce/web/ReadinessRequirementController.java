package com.fieldservice.workforce.web;

import com.fieldservice.workforce.internal.ReadinessReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/readiness-requirements")
@PreAuthorize("hasRole('ADMIN')")
public class ReadinessRequirementController {

    private final ReadinessReportService reportService;

    public ReadinessRequirementController(ReadinessReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public List<ReadinessRequirementResponse> list() {
        return reportService.listRequirements();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReadinessRequirementResponse create(@Valid @RequestBody ReadinessRequirementRequest request) {
        return reportService.createRequirement(request);
    }

    @PutMapping("/{id}")
    public ReadinessRequirementResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ReadinessRequirementRequest request) {
        return reportService.updateRequirement(id, request);
    }

    @DeleteMapping("/{id}")
    public ReadinessRequirementResponse deactivate(@PathVariable UUID id) {
        return reportService.deactivateRequirement(id);
    }
}
