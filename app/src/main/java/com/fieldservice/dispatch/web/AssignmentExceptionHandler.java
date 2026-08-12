package com.fieldservice.dispatch.web;

import com.fieldservice.dispatch.internal.AssignmentValidationException;
import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.api.exception.ProviderDegradedException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.workorder.lifecycle.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.lifecycle.WorkOrderVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exception handler scoped to the dispatch web package.
 *
 * <p>{@code @Order(9)} runs before {@link com.fieldservice.workorder.web.WorkOrderExceptionHandler}
 * (order 10) so dispatch-specific mappings take priority.
 */
@Order(9)
@RestControllerAdvice(basePackages = "com.fieldservice.dispatch.web")
public class AssignmentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AssignmentExceptionHandler.class);

    @ExceptionHandler(AssignmentValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(AssignmentValidationException ex) {
        String traceId = resolveTraceId();
        log.info("assignment_validation_failed field={} code={} trace_id={}", ex.getField(), ex.getCode(), traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(
                        ErrorCode.VALIDATION_FAILED,
                        ex.getMessage(),
                        List.of(new FieldError(ex.getField(), ex.getCode())),
                        traceId));
    }

    @ExceptionHandler(BusinessGuardException.class)
    public ResponseEntity<ApiErrorResponse> handleGuard(BusinessGuardException ex) {
        String traceId = resolveTraceId();
        List<FieldError> details = ex.getGuardSubCode() != null
                ? List.of(new FieldError("guardSubCode", ex.getGuardSubCode()))
                : List.of();
        log.info("assignment_guard_refused code={} trace_id={}", ex.getGuardSubCode(), traceId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(
                        ErrorCode.CERTIFICATION_NOT_CURRENT,
                        ex.getMessage(),
                        details,
                        traceId));
    }

    @ExceptionHandler(IllegalWorkOrderTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalTransition(IllegalWorkOrderTransitionException ex) {
        String traceId = resolveTraceId();
        List<FieldError> legalEventErrors = ex.getLegalEvents().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(e -> new FieldError("legalNextEvents", e.name()))
                .collect(Collectors.toList());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(
                        ErrorCode.WORK_ORDER_ILLEGAL_TRANSITION,
                        ex.getMessage(),
                        legalEventErrors,
                        traceId));
    }

    @ExceptionHandler(WorkOrderVersionConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleVersionConflict(WorkOrderVersionConflictException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(
                        ErrorCode.WORK_ORDER_VERSION_CONFLICT,
                        "The work order was modified by another request. Retry with the latest version.",
                        traceId));
    }

    @ExceptionHandler({ScopedAccessDeniedException.class, ForbiddenException.class})
    public ResponseEntity<ApiErrorResponse> handleForbidden(Exception ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.forbidden(traceId));
    }

    @ExceptionHandler(ProviderDegradedException.class)
    public ResponseEntity<ApiErrorResponse> handleProviderDegraded(ProviderDegradedException ex) {
        String traceId = resolveTraceId();
        log.warn("assignment_provider_degraded message={} trace_id={}", ex.getMessage(), traceId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(
                        ErrorCode.PROVIDER_DEGRADED,
                        "Eligibility data is temporarily unavailable. Assignment refused to ensure certification accuracy.",
                        traceId));
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
