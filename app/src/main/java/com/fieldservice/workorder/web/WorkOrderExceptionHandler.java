package com.fieldservice.workorder.web;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.workorder.lifecycle.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.lifecycle.WorkOrderVersionConflictException;
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
 * Handles work-order lifecycle exceptions with domain-specific error codes.
 *
 * <p>{@code @Order(10)} gives this advice priority over {@link com.fieldservice.platform.web.GlobalExceptionHandler}
 * (which has no explicit order) so work-order controllers receive domain-specific codes
 * instead of generic platform codes for shared exception types like
 * {@link BusinessGuardException}.
 */
@Order(10)
@RestControllerAdvice(basePackages = "com.fieldservice.workorder.web")
public class WorkOrderExceptionHandler {

    @ExceptionHandler(IllegalWorkOrderTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalWorkOrderTransition(
            IllegalWorkOrderTransitionException ex) {
        String traceId = resolveTraceId();
        List<FieldError> legalEventErrors = ex.getLegalEvents().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(e -> new FieldError("legalNextEvents", e.name()))
                .collect(Collectors.toList());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.WORK_ORDER_ILLEGAL_TRANSITION,
                        ex.getMessage(), legalEventErrors, traceId));
    }

    @ExceptionHandler(BusinessGuardException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessGuard(BusinessGuardException ex) {
        String traceId = resolveTraceId();
        List<FieldError> details = ex.getGuardSubCode() != null
                ? List.of(new FieldError("guardSubCode", ex.getGuardSubCode()))
                : List.of();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.WORK_ORDER_GUARD_REFUSED,
                        ex.getMessage(), details, traceId));
    }

    @ExceptionHandler(WorkOrderVersionConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleVersionConflict(WorkOrderVersionConflictException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(ErrorCode.WORK_ORDER_VERSION_CONFLICT,
                        "The work order was modified by another request. Retry with the latest version.",
                        traceId));
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
