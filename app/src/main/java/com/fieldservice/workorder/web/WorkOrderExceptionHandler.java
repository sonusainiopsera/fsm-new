package com.fieldservice.workorder.web;

import com.fieldservice.inventory.application.InsufficientStockException;
import com.fieldservice.inventory.application.InvalidStockMovementException;
import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.workorder.application.WorkOrderReferentialException;
import com.fieldservice.workorder.holds.HoldReasonValidationException;
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

    private static final Logger log = LoggerFactory.getLogger(WorkOrderExceptionHandler.class);

    @ExceptionHandler(WorkOrderReferentialException.class)
    public ResponseEntity<ApiErrorResponse> handleReferentialException(WorkOrderReferentialException ex) {
        String traceId = resolveTraceId();
        log.warn("work_order_referential_violation code={} field={} trace_id={}", ex.getCode(), ex.getField(), traceId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.GUARD_REFUSED,
                        ex.getMessage(),
                        List.of(new FieldError(ex.getField(), ex.getCode())),
                        traceId));
    }

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

    @ExceptionHandler(HoldReasonValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHoldReasonValidation(HoldReasonValidationException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.HOLD_REASON_INVALID,
                        ex.getMessage(),
                        List.of(new FieldError("holdReasonCode", ex.getSubmittedCode())),
                        traceId));
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

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientStock(InsufficientStockException ex) {
        String traceId = resolveTraceId();
        List<FieldError> fieldErrors = new java.util.ArrayList<>();
        for (int i = 0; i < ex.getShortfalls().size(); i++) {
            var s = ex.getShortfalls().get(i);
            fieldErrors.add(new FieldError(
                    "lines[" + i + "].quantity",
                    "requested " + s.requested() + ", available " + s.available()));
        }
        return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Trace-Id", traceId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.INSUFFICIENT_STOCK,
                        ex.getMessage(), fieldErrors, traceId));
    }

    @ExceptionHandler(InvalidStockMovementException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidStockMovement(InvalidStockMovementException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.INVALID_STOCK_MOVEMENT,
                        ex.getMessage(),
                        List.of(new FieldError(ex.getField(), ex.getMessage())),
                        traceId));
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
