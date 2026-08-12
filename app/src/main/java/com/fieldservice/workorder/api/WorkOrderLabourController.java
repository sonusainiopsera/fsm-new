package com.fieldservice.workorder.api;

import com.fieldservice.domain.workorder.LabourTimeRecord;
import com.fieldservice.domain.workorder.LabourTimeRecordRepository;
import com.fieldservice.platform.security.AccessScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Records technician labour time against a work order (WO-157).
 *
 * <p>Accepts either an explicit {@code durationMinutes} or a {@code startedAt}/{@code endedAt}
 * pair from which duration is computed. At least one of these forms must be present.
 *
 * <p>Idempotency is enforced at the filter layer ({@code IdempotencyKeyFilter}); replaying the
 * same {@code Idempotency-Key} returns the original response and creates exactly one record.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{id}/labour")
@PreAuthorize("isAuthenticated()")
public class WorkOrderLabourController {

    private final LabourTimeRecordRepository labourRepository;
    private final AccessScopeResolver scopeResolver;

    public WorkOrderLabourController(LabourTimeRecordRepository labourRepository,
                                     AccessScopeResolver scopeResolver) {
        this.labourRepository = labourRepository;
        this.scopeResolver = scopeResolver;
    }

    @PostMapping
    public ResponseEntity<LabourResponse> logLabour(
            @PathVariable UUID id,
            @Valid @RequestBody LabourRequest request) {

        int resolvedMinutes;
        Instant workDate;

        if (request.durationMinutes() != null && request.durationMinutes() > 0) {
            resolvedMinutes = request.durationMinutes();
            workDate = request.startedAt() != null ? request.startedAt() : Instant.now();
        } else if (request.startedAt() != null && request.endedAt() != null) {
            if (!request.endedAt().isAfter(request.startedAt())) {
                throw new com.fieldservice.platform.exception.BusinessGuardException(
                        "endedAt must be after startedAt");
            }
            long minutes = Duration.between(request.startedAt(), request.endedAt()).toMinutes();
            if (minutes <= 0) {
                throw new com.fieldservice.platform.exception.BusinessGuardException(
                        "Labour duration must be at least 1 minute");
            }
            resolvedMinutes = (int) minutes;
            workDate = request.startedAt();
        } else {
            throw new com.fieldservice.platform.exception.BusinessGuardException(
                    "Provide durationMinutes or both startedAt and endedAt");
        }

        UUID technicianId = scopeResolver.resolve().technicianId();
        if (technicianId == null) {
            // Non-technician actor logging time — use userId as fallback
            technicianId = scopeResolver.resolve().userId();
        }

        LabourTimeRecord record = new LabourTimeRecord();
        record.setWorkOrderId(id);
        record.setTechnicianId(technicianId);
        record.setMinutes(resolvedMinutes);
        record.setWorkDate(workDate);
        record.setNote(request.note());
        labourRepository.save(record);

        return ResponseEntity.status(201).body(
                new LabourResponse(record.getId(), id, resolvedMinutes, workDate, request.note()));
    }

    public record LabourRequest(
            Instant startedAt,
            Instant endedAt,
            Integer durationMinutes,
            @Size(max = 500) String note
    ) {}

    public record LabourResponse(
            UUID id,
            UUID workOrderId,
            int minutes,
            Instant workDate,
            String note
    ) {}
}
