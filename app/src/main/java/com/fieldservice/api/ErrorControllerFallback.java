package com.fieldservice.api;

import com.fieldservice.platform.api.ErrorEnvelope;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Fallback error controller for errors that occur in the servlet filter chain before
 * a request reaches a {@code @RestControllerAdvice}.
 *
 * <p>Spring Boot forwards such errors (e.g., 400 from a malformed URL, 404 for an
 * unmapped path, or filter-level auth failures that bypass the exception handler) to
 * {@code /error}. This controller intercepts those forwards and returns a structured
 * {@link ErrorEnvelope} rather than Spring Boot's default {@code BasicErrorController}
 * JSON, which may include stack traces or Spring-internal message strings.
 *
 * <p>Never discloses internal error details; always returns a safe, stable code.
 */
@RestController
public class ErrorControllerFallback implements ErrorController {

    private static final String ERROR_PATH = "/error";

    @RequestMapping(ERROR_PATH)
    public ResponseEntity<ErrorEnvelope> handleError(HttpServletRequest request) {
        Integer statusCode = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = (statusCode != null) ? statusCode : HttpStatus.INTERNAL_SERVER_ERROR.value();

        String tid = traceId();
        String code;
        String message;

        if (status == HttpStatus.UNAUTHORIZED.value()) {
            code = ErrorEnvelope.Code.UNAUTHENTICATED;
            message = "Authentication required.";
        } else if (status == HttpStatus.FORBIDDEN.value()) {
            code = ErrorEnvelope.Code.FORBIDDEN;
            message = "Access denied.";
        } else if (status == HttpStatus.NOT_FOUND.value()) {
            code = ErrorEnvelope.Code.NOT_FOUND;
            message = "The requested resource was not found.";
        } else if (status >= 400 && status < 500) {
            code = ErrorEnvelope.Code.VALIDATION_FAILED;
            message = "The request could not be processed.";
        } else {
            code = ErrorEnvelope.Code.INTERNAL_ERROR;
            message = "An unexpected error occurred.";
        }

        return ResponseEntity.status(status)
                .header("X-Trace-Id", tid)
                .body(new ErrorEnvelope(code, message, tid, Instant.now()));
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "none";
    }
}
