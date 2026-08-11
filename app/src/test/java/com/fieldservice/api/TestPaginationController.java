package com.fieldservice.api;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.pagination.SpecificationPageService;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.pagination.SortField;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Test-only controller that exposes a paginated work-order endpoint.
 *
 * <p>Used by {@link com.fieldservice.pagination.WorkOrderPaginationTest} to exercise
 * the full pagination stack against a real Testcontainers database.
 *
 * <p>When no sort is specified the default is {@code createdAt DESC, id ASC} — this
 * aligns with the composite index and enables keyset pagination beyond page 20.
 */
@TestComponent
@RestController
@RequestMapping("/test/work-orders")
@Profile("test")
public class TestPaginationController {

    static final SortAllowList WORK_ORDER_SORTS = SortAllowList.of(
            "createdAt",   "createdAt",
            "state",       "state",
            "priority",    "priority",
            "title",       "title",
            "slaDeadline", "slaDeadline"
    );

    private static final List<SortField> DEFAULT_SORT = List.of(
            new SortField("createdAt", Sort.Direction.DESC)
    );

    private final SpecificationPageService pageService;
    private final WorkOrderRepository workOrderRepository;

    public TestPaginationController(
            SpecificationPageService pageService,
            WorkOrderRepository workOrderRepository) {
        this.pageService = pageService;
        this.workOrderRepository = workOrderRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<PagedResponse<WorkOrder>> list(
            PageQuery pageQuery,
            HttpServletRequest request) {

        // Apply default sort when the client supplies none
        PageQuery effective = pageQuery.sort().isEmpty()
                ? new PageQuery(pageQuery.page(), pageQuery.size(), DEFAULT_SORT, pageQuery.cursor())
                : pageQuery;

        PagedResponse<WorkOrder> response = pageService.findPage(
                WorkOrder.class,
                null,
                effective,
                WORK_ORDER_SORTS,
                workOrderRepository,
                "work_order",
                request);

        return ResponseEntity.ok(response);
    }
}
