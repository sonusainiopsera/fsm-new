package com.fieldservice.workorder.web;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.workorder.lifecycle.IllegalWorkOrderTransitionException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/** Handles work-order lifecycle exceptions not covered by the platform handler. */
@RestControllerAdvice
public class WorkOrderExceptionHandler {

    @ExceptionHandler(IllegalWorkOrderTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalWorkOrderTransition(
            IllegalWorkOrderTransitionException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(ErrorCode.WORK_ORDER_ILLEGAL_TRANSITION,
                        ex.getMessage(), traceId));
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
