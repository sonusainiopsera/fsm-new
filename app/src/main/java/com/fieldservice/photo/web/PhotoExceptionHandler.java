package com.fieldservice.photo.web;

import com.fieldservice.photo.api.PhotoStorageUnavailableException;
import com.fieldservice.photo.application.ContentTooLargeException;
import com.fieldservice.photo.application.DisallowedContentTypeException;
import com.fieldservice.photo.application.IntentExpiredException;
import com.fieldservice.photo.application.IntentNotFoundException;
import com.fieldservice.photo.application.ObjectVerificationException;
import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

/**
 * Maps photo-upload domain exceptions to structured API error responses.
 *
 * <p>Presigned URLs are never included in error messages or logged here.
 */
@Order(10)
@RestControllerAdvice(basePackages = "com.fieldservice.photo.web")
public class PhotoExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PhotoExceptionHandler.class);

    @ExceptionHandler(DisallowedContentTypeException.class)
    public ResponseEntity<ApiErrorResponse> handleDisallowedContentType(DisallowedContentTypeException ex) {
        String traceId = resolveTraceId();
        log.warn("photo_disallowed_type submitted={} traceId={}", ex.getSubmittedType(), traceId);
        return badRequest(ErrorCode.PHOTO_DISALLOWED_CONTENT_TYPE, ex.getMessage(),
                List.of(new FieldError("contentType", ex.getMessage())), traceId);
    }

    @ExceptionHandler(ContentTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handleContentTooLarge(ContentTooLargeException ex) {
        String traceId = resolveTraceId();
        log.warn("photo_too_large submitted={} max={} traceId={}", ex.getSubmitted(), ex.getMaxBytes(), traceId);
        return badRequest(ErrorCode.PHOTO_CONTENT_TOO_LARGE, ex.getMessage(),
                List.of(new FieldError("contentLength", ex.getMessage())), traceId);
    }

    @ExceptionHandler(IntentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleIntentNotFound(IntentNotFoundException ex) {
        String traceId = resolveTraceId();
        log.warn("photo_intent_not_found traceId={}", traceId);
        return unprocessable(ErrorCode.PHOTO_INTENT_NOT_FOUND,
                "No upload intent was found for the provided storage key.", traceId);
    }

    @ExceptionHandler(IntentExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleIntentExpired(IntentExpiredException ex) {
        String traceId = resolveTraceId();
        log.info("photo_intent_expired traceId={}", traceId);
        return unprocessable(ErrorCode.PHOTO_INTENT_EXPIRED,
                ex.getMessage(), traceId);
    }

    @ExceptionHandler(ObjectVerificationException.class)
    public ResponseEntity<ApiErrorResponse> handleObjectVerification(ObjectVerificationException ex) {
        String traceId = resolveTraceId();
        log.warn("photo_object_mismatch traceId={}", traceId);
        return unprocessable(ErrorCode.PHOTO_OBJECT_MISMATCH, ex.getMessage(), traceId);
    }

    @ExceptionHandler(PhotoStorageUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleStorageUnavailable(PhotoStorageUnavailableException ex) {
        String traceId = resolveTraceId();
        log.error("photo_storage_unavailable traceId={}", traceId, ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(ErrorCode.PHOTO_STORAGE_UNAVAILABLE,
                        "Photo storage is temporarily unavailable. Please try again.", traceId));
    }

    // ---- Helpers -----------------------------------------------------------

    private ResponseEntity<ApiErrorResponse> badRequest(ErrorCode code, String message,
                                                          List<FieldError> errors, String traceId) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.withFieldErrors(code, message, errors, traceId));
    }

    private ResponseEntity<ApiErrorResponse> unprocessable(ErrorCode code, String message,
                                                             String traceId) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(code, message, traceId));
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
