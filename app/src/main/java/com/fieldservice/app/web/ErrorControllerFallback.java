package com.fieldservice.app.web;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Catches filter-stage failures that never reach {@code @RestControllerAdvice},
 * ensuring the uniform {@link ApiErrorResponse} envelope is returned instead of
 * Spring Boot's default HTML error page.
 */
@RestController
@RequestMapping("${server.error.path:/error}")
public class ErrorControllerFallback implements ErrorController {

    @RequestMapping
    public ResponseEntity<ApiErrorResponse> handleError(HttpServletRequest request) {
        Integer statusCode = (Integer) request.getAttribute(
                "jakarta.servlet.error.status_code");
        HttpStatus status = (statusCode != null)
                ? HttpStatus.resolve(statusCode) : null;
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String traceId = resolveTraceId();
        ApiErrorResponse body = switch (status.series()) {
            case CLIENT_ERROR -> ApiErrorResponse.of(
                    ErrorCode.VALIDATION_FAILED, "The request could not be processed.", traceId);
            default -> ApiErrorResponse.of(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred. Please contact support if this persists.", traceId);
        };

        return ResponseEntity.status(status)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
