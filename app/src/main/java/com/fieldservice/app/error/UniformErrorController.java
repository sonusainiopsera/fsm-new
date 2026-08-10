package com.fieldservice.app.error;

import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.ErrorResponse;
import com.fieldservice.platform.web.TraceIdFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fallback error controller for failures that occur in servlet filters before
 * the {@link com.fieldservice.platform.web.GlobalExceptionHandler} is reachable
 * (e.g. Spring Security filter-chain rejections, request-size limit exceeded).
 *
 * <p>Replaces Spring Boot's default {@code BasicErrorController} so that
 * filter-stage errors still return the uniform JSON envelope rather than the
 * default HTML Whitelabel error page.
 */
@RestController
@RequestMapping("${server.error.path:${error.path:/error}}")
public class UniformErrorController implements ErrorController {

    @RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = statusAttr instanceof Integer code ? code : 500;
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;

        String traceId = traceId();
        ErrorCode errorCode = mapStatusToCode(status);
        String message = mapStatusToMessage(status);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .body(ErrorResponse.of(errorCode, message, traceId));
    }

    private static ErrorCode mapStatusToCode(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED          -> ErrorCode.FORBIDDEN;
            case FORBIDDEN             -> ErrorCode.FORBIDDEN;
            case NOT_FOUND             -> ErrorCode.NOT_FOUND;
            case CONFLICT              -> ErrorCode.CONFLICT;
            case UNPROCESSABLE_ENTITY  -> ErrorCode.GUARD_REFUSED;
            case TOO_MANY_REQUESTS     -> ErrorCode.RATE_LIMITED;
            case SERVICE_UNAVAILABLE   -> ErrorCode.PROVIDER_DEGRADED;
            default -> status.is4xxClientError()
                    ? ErrorCode.VALIDATION_FAILED
                    : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static String mapStatusToMessage(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED         -> "Authentication required";
            case FORBIDDEN            -> "Access denied";
            case NOT_FOUND            -> "Resource not found";
            case METHOD_NOT_ALLOWED   -> "Method not allowed";
            case TOO_MANY_REQUESTS    -> "Too many requests";
            case SERVICE_UNAVAILABLE  -> "Service temporarily unavailable";
            default -> status.is5xxServerError()
                    ? "An unexpected error occurred"
                    : "Bad request";
        };
    }

    private static String traceId() {
        String id = MDC.get(TraceIdFilter.MDC_TRACE_KEY);
        return id != null ? id : "no-trace";
    }
}
