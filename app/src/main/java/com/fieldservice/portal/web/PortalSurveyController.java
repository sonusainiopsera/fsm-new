package com.fieldservice.portal.web;

import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.portal.csat.CsatResponse;
import com.fieldservice.portal.csat.CsatResponseRequest;
import com.fieldservice.portal.csat.CsatResponseView;
import com.fieldservice.portal.csat.CsatSurvey;
import com.fieldservice.portal.csat.CsatSurveyService;
import com.fieldservice.portal.csat.CsatSurveyView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Customer portal endpoints for CSAT survey listing and response submission.
 *
 * <ul>
 *   <li>GET  /api/v1/portal/surveys          — list surveys for the authenticated account</li>
 *   <li>POST /api/v1/portal/surveys/{id}/response — submit a response (201 Created)</li>
 * </ul>
 *
 * <p>Exception mapping for 409 (CSAT_ALREADY_ANSWERED) and 422 (CSAT_WINDOW_EXPIRED) is
 * handled by {@link PortalExceptionAdvice}.
 */
@RestController
@RequestMapping("/api/v1/portal/surveys")
public class PortalSurveyController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE     = 50;
    private static final String BASE_PATH      = "/api/v1/portal/surveys";

    private final CsatSurveyService surveyService;

    public PortalSurveyController(CsatSurveyService surveyService) {
        this.surveyService = surveyService;
    }

    /**
     * GET /api/v1/portal/surveys
     *
     * <p>Returns the authenticated account's CSAT surveys, newest first.
     * Page size is server-enforced at a maximum of 50. Offset-based pagination.
     */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PagedResponse<CsatSurveyView>> listSurveys(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page, clampedSize,
                Sort.by(Sort.Direction.DESC, "issuedAt"));

        Page<CsatSurvey> surveyPage = surveyService.findSurveys(pageable);

        List<CsatSurveyView> data = surveyPage.getContent().stream()
                .map(CsatSurveyView::from)
                .toList();

        PageMeta meta  = PageMeta.of(page, clampedSize, surveyPage.getTotalElements());
        String prevLink = page > 0 ? offsetLink(page - 1, clampedSize) : null;
        String nextLink = surveyPage.hasNext() ? offsetLink(page + 1, clampedSize) : null;

        return ResponseEntity.ok(PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink)));
    }

    /**
     * POST /api/v1/portal/surveys/{id}/response
     *
     * <p>Submits a CSAT response for the given survey. Returns 201 Created on success.
     * Returns 409 if already answered, 422 if the response window has expired.
     */
    @PostMapping("/{id}/response")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<CsatResponseView> submitResponse(
            @PathVariable UUID id,
            @Valid @RequestBody CsatResponseRequest request) {

        CsatResponse response = surveyService.submitResponse(id, request);
        CsatResponseView view = CsatResponseView.from(response);

        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + id + "/response"))
                .body(view);
    }

    private static String offsetLink(int pg, int sz) {
        return BASE_PATH + "?page=" + pg + "&size=" + sz;
    }
}
