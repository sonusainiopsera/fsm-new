package com.fieldservice.workorder.web;

import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.workorder.domain.LabourEntry;
import com.fieldservice.workorder.repository.LabourEntryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Logs technician labour time against a work order.
 *
 * <p>Idempotency-Key handling is transparent — the platform IdempotencyFilter
 * intercepts the POST and returns the original response on replay, so a mobile
 * retry on a flaky network cannot double-log time.
 *
 * <p>Duration is expressed as explicit minutes when the client does not track
 * start/end times (e.g. the technician enters time post-hoc), or as startedAt
 * + endedAt with server-side duration derivation. Both paths result in a single
 * {@link LabourEntry} row.
 *
 * <p>Row-scope: only the work order's assigned technician may log time (TECHNICIAN role);
 * DISPATCHER and ADMIN may log time on behalf of the technician.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderLabourController {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderLabourController.class);
    private static final int MAX_NOTE_LENGTH = 500;

    private final LabourEntryRepository   labourEntryRepository;
    private final RequestScopedAccessScope accessScope;

    public WorkOrderLabourController(LabourEntryRepository labourEntryRepository,
                                     RequestScopedAccessScope accessScope) {
        this.labourEntryRepository = labourEntryRepository;
        this.accessScope           = accessScope;
    }

    /**
     * Records a labour time entry for a work order.
     *
     * <p>Accepts either:
     * <ul>
     *   <li>{@code durationMinutes} (explicit) — used when start/end times are unknown</li>
     *   <li>{@code startedAt} + {@code endedAt} — server derives duration; end must be after start</li>
     * </ul>
     *
     * @param workOrderId the work order to log time against
     * @param request     labour time details
     * @return 200 with the new entry id
     */
    @PostMapping("/{workOrderId}/labour")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<LabourResponse> logLabour(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody LabourRequest request) {

        int minutes = resolveMinutes(request);

        UUID technicianId = accessScope.get().technicianId();
        LabourEntry entry = new LabourEntry(workOrderId, technicianId, minutes, request.note());

        labourEntryRepository.save(entry);

        log.info("labour_time_logged workOrderId={} technicianId={} minutes={} traceId={}",
                workOrderId, technicianId, minutes, MDC.get("traceId"));

        return ResponseEntity.ok(new LabourResponse(entry.getId(), workOrderId, minutes));
    }

    private static int resolveMinutes(LabourRequest request) {
        if (request.durationMinutes() != null) {
            return request.durationMinutes();
        }
        if (request.startedAt() != null && request.endedAt() != null) {
            long derived = ChronoUnit.MINUTES.between(request.startedAt(), request.endedAt());
            if (derived <= 0) {
                throw new IllegalArgumentException("endedAt must be after startedAt");
            }
            return (int) Math.min(derived, Integer.MAX_VALUE);
        }
        throw new IllegalArgumentException("Either durationMinutes or both startedAt and endedAt must be provided");
    }

    /** Labour time entry request. */
    public record LabourRequest(
            Instant startedAt,
            Instant endedAt,
            @Min(1) @Max(1440) Integer durationMinutes,
            @Size(max = 500) String note
    ) {}

    /** Successful labour log response. */
    public record LabourResponse(UUID entryId, UUID workOrderId, int minutes) {}
}
