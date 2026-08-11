package com.fieldservice.portal.web;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.portal.csat.CsatSurveyService;
import com.fieldservice.portal.web.dto.SubmitResponseRequest;
import com.fieldservice.portal.web.dto.SubmitResponseResponse;
import com.fieldservice.portal.web.dto.SurveyRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Customer-facing CSAT survey endpoints (WO-173).
 *
 * <p>GET  /api/v1/portal/surveys       — paginated list of surveys for the current customer.<br>
 * POST /api/v1/portal/surveys/{id}/response — submit a response to a pending survey.
 *
 * <p>Both endpoints require the {@code CUSTOMER} role.
 * Access control is account-scoped: surveys belonging to other accounts are never disclosed.
 */
@RestController
@RequestMapping("/api/v1/portal/surveys")
@PreAuthorize("hasAuthority('CUSTOMER')")
public class PortalSurveyController {

    private final CsatSurveyService surveyService;

    public PortalSurveyController(CsatSurveyService surveyService) {
        this.surveyService = surveyService;
    }

    /**
     * Returns a paginated list of CSAT surveys for the authenticated customer.
     *
     * @param pageQuery page/size parameters auto-resolved (size clamped to 50)
     * @param request   raw HTTP request for building next/prev links
     * @return 200 with {@code { data, page, links }} envelope
     */
    @GetMapping
    public ResponseEntity<PagedResponse<SurveyRow>> listSurveys(
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(surveyService.findSurveys(pageQuery, request));
    }

    /**
     * Submits a response to an open CSAT survey.
     *
     * @param surveyId the survey to respond to
     * @param request  validated response payload (score 1-5, optional npsScore 0-10, comment ≤1000 chars)
     * @return 201 Created with {@code { data: { surveyId, submittedAt } }}
     */
    @PostMapping("/{id}/response")
    public ResponseEntity<Map<String, SubmitResponseResponse>> submitResponse(
            @PathVariable("id") UUID surveyId,
            @RequestBody @Valid SubmitResponseRequest request) {
        SubmitResponseResponse result = surveyService.submitResponse(surveyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", result));
    }
}
