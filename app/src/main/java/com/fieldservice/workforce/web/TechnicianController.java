package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.api.TechnicianSummary;
import com.fieldservice.workforce.internal.WorkforceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/technicians")
public class TechnicianController {

    private final WorkforceService workforceService;

    public TechnicianController(WorkforceService workforceService) {
        this.workforceService = workforceService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<TechnicianSummary>> listActive(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        int cappedSize = Math.min(Math.max(size, 1), 50);
        List<TechnicianSummary> all = workforceService.findAllActive();
        int total = all.size();
        int fromIdx = Math.min(page * cappedSize, total);
        int toIdx   = Math.min(fromIdx + cappedSize, total);
        List<TechnicianSummary> data = all.subList(fromIdx, toIdx);
        PageMeta meta = PageMeta.of(page, cappedSize, total);
        boolean hasNext = toIdx < total;
        boolean hasPrev = page > 0;
        String nextLink = hasNext ? "/api/v1/technicians?page=" + (page + 1) + "&size=" + cappedSize : null;
        String prevLink = hasPrev ? "/api/v1/technicians?page=" + (page - 1) + "&size=" + cappedSize : null;
        return ResponseEntity.ok(PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TechnicianSummary> getById(@PathVariable UUID id) {
        return workforceService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TechnicianResponse create(@Valid @RequestBody TechnicianRequest request) {
        return workforceService.createTechnician(request);
    }

    @PutMapping("/{id}")
    public TechnicianResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody TechnicianRequest request) {
        return workforceService.updateTechnicianProfile(id, request);
    }

    @DeleteMapping("/{id}")
    public TechnicianResponse deactivate(@PathVariable UUID id) {
        return workforceService.deactivateTechnician(id);
    }

    @PutMapping("/{id}/skills")
    public List<SkillRowResult> upsertSkills(@PathVariable UUID id,
                                              @Valid @RequestBody List<SkillItemRequest> items) {
        return workforceService.upsertTechnicianSkills(id, items);
    }

    @PutMapping("/{id}/availability")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceAvailability(@PathVariable UUID id,
                                    @Valid @RequestBody List<AvailabilityWindowRequest> windows) {
        workforceService.replaceAvailabilityWindows(id, windows);
    }

    @PostMapping("/{id}/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public void addAbsence(@PathVariable UUID id,
                           @Valid @RequestBody AbsenceRequest request) {
        workforceService.addAbsence(id, request);
    }

    @PostMapping("/{id}/position")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void updatePosition(@PathVariable UUID id,
                                @Valid @RequestBody PositionRequest request) {
        workforceService.updatePosition(id, request);
    }
}
