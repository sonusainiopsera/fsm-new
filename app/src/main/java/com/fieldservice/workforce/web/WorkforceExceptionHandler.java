package com.fieldservice.workforce.web;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.workforce.internal.NoActiveJobException;
import com.fieldservice.workforce.internal.PositionRateLimitedException;
import com.fieldservice.workforce.internal.PositionValidationException;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@Order(10)
@RestControllerAdvice(basePackages = "com.fieldservice.workforce.web")
public class WorkforceExceptionHandler {

    @ExceptionHandler(PositionValidationException.class)
    public ResponseEntity<ApiErrorResponse> handlePositionValidation(PositionValidationException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                        ex.getMessage(), ex.getFieldErrors(), traceId));
    }

    @ExceptionHandler(NoActiveJobException.class)
    public ResponseEntity<ApiErrorResponse> handleNoActiveJob(NoActiveJobException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                .body(ApiErrorResponse.of(ErrorCode.NO_ACTIVE_JOB, ex.getMessage(), traceId));
    }

    @ExceptionHandler(PositionRateLimitedException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimited(PositionRateLimitedException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiErrorResponse.of(ErrorCode.POSITION_RATE_LIMITED, ex.getMessage(), traceId));
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
