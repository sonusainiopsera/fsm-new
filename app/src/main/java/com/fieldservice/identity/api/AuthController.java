package com.fieldservice.identity.api;

import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.identity.api.dto.LoginResponse;
import com.fieldservice.identity.application.LoginAttemptTracker;
import com.fieldservice.identity.application.LoginService;
import com.fieldservice.platform.api.ErrorEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Credential-based token issuance")
public class AuthController {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials.";
    private static final String AUTH_PATH = "/api/v1/auth";

    private final LoginService loginService;
    private final long refreshTokenTtlSeconds;

    public AuthController(LoginService loginService,
                          com.fieldservice.identity.config.AuthProperties authProperties) {
        this.loginService = loginService;
        this.refreshTokenTtlSeconds = authProperties.refreshToken().ttl().getSeconds();
    }

    @Operation(operationId = "login", summary = "Authenticate with email and password")
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletResponse httpResponse) {
        LoginService.LoginResult result;
        try {
            result = loginService.login(request.email(), request.password());
        } catch (LoginAttemptTracker.LoginAttemptStoreException e) {
            String tid = traceId();
            httpResponse.setHeader("X-Trace-Id", tid);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorEnvelope(
                            ErrorEnvelope.Code.AUTH_DEPENDENCY_UNAVAILABLE,
                            "Authentication service is temporarily unavailable. Please try again shortly.",
                            tid, Instant.now()));
        }

        if (result instanceof LoginService.LoginResult.Failure failure) {
            String tid = traceId();
            httpResponse.setHeader("X-Trace-Id", tid);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorEnvelope(
                            ErrorEnvelope.Code.INVALID_CREDENTIALS,
                            INVALID_CREDENTIALS_MESSAGE,
                            tid, Instant.now()));
        }

        LoginService.LoginResult.Success success = (LoginService.LoginResult.Success) result;

        String cookieValue = buildRefreshCookie(success.refreshHandle());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieValue);

        String tid = traceId();
        httpResponse.setHeader("X-Trace-Id", tid);

        LoginResponse body = LoginResponse.of(
                success.accessToken(),
                success.expiresIn(),
                success.user().getId(),
                success.user().getDisplayName(),
                success.roles());

        return ResponseEntity.ok(body);
    }

    private String buildRefreshCookie(String refreshHandle) {
        return String.format(
                "refreshToken=%s; HttpOnly; Secure; SameSite=Strict; Path=%s; Max-Age=%d",
                refreshHandle, AUTH_PATH, refreshTokenTtlSeconds);
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
        List<com.fieldservice.platform.api.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors().stream()
                .map(fe -> new com.fieldservice.platform.api.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"))
                .toList();
        String tid = traceId();
        return ResponseEntity.badRequest()
                .header("X-Trace-Id", tid)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.VALIDATION_FAILED,
                        "Request validation failed.",
                        fieldErrors,
                        tid,
                        Instant.now()));
    }
}
