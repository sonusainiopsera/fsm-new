package com.fieldservice.workorder.api;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workorder.holds.HoldReasonResponse;
import com.fieldservice.workorder.holds.HoldReasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the active hold reason vocabulary under the work orders resource group.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work Orders", description = "Work order hold reason vocabulary")
public class HoldReasonController {

    private final HoldReasonService holdReasonService;

    public HoldReasonController(HoldReasonService holdReasonService) {
        this.holdReasonService = holdReasonService;
    }

    @Operation(
            operationId = "listHoldReasons",
            summary = "List active hold reason codes in sort order"
    )
    @GetMapping("/hold-reasons")
    public ResponseEntity<PagedResponse<HoldReasonResponse>> listHoldReasons() {
        List<HoldReasonResponse> reasons = holdReasonService.getActiveReasons();
        PageMeta meta = PageMeta.of(0, reasons.size(), reasons.size());
        return ResponseEntity.ok(PagedResponse.of(reasons, meta, PageLinks.none()));
    }
}
