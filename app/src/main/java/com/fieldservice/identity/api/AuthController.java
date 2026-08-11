package com.fieldservice.identity.api;

import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.identity.api.dto.LoginResponse;
import com.fieldservice.identity.api.dto.RefreshResponse;
import com.fieldservice.identity.application.LoginService;
import com.fieldservice.identity.application.RefreshTokenService;
import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.exception.InvalidCredentialsException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Authentication endpoints: login and refresh-token rotation.
 *
 * <p>POST /api/v1/auth/login accepts credentials, returns a 15-minute access token in the
 * body and a 7-day refresh handle in an HttpOnly cookie.
 *
 * <p>POST /api/v1/auth/refresh exchanges the HttpOnly cookie for a new access token and
 * a rotated cookie. The endpoint reads the handle ONLY from the cookie — a handle supplied
 * in a body, header, or query parameter is rejected with 401. Reuse of a consumed handle
 * triggers immediate family revocation and a SIEM security event. This operation is
 * deliberately non-idempotent: do not retry a consumed handle.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Authentication and token management")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME  = "refresh_token";
    private static final String REFRESH_COOKIE_PATH  = "/api/v1/auth";
    private static final long   REFRESH_MAX_AGE_SECS = Duration.ofDays(7).getSeconds();

    private final LoginService        loginService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(LoginService loginService, RefreshTokenService refreshTokenService) {
        this.loginService        = loginService;
        this.refreshTokenService = refreshTokenService;
    }

    @Operation(operationId = "login", summary = "Authenticate with email and password")
    @PostMapping(value = "/login",
                 consumes = "application/json",
                 produces = "application/json")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = resolveClientIp(httpRequest);

        LoginService.LoginResult result =
                loginService.login(request.email(), request.password(), clientIp);

        List<String> roleNames = result.roles().stream()
                .map(Enum::name)
                .toList();

        LoginResponse.User userSummary = new LoginResponse.User(
                result.user().getId(),
                result.user().getDisplayName(),
                roleNames);

        int expiresIn = (int) ChronoUnit.SECONDS.between(
                Instant.now(), result.tokens().accessTokenExp());

        LoginResponse body = LoginResponse.of(
                result.tokens().accessToken(),
                expiresIn,
                userSummary);

        ResponseCookie refreshCookie = refreshCookie(result.tokens().refreshHandle());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(body);
    }

    /**
     * Rotates the refresh handle from the HttpOnly cookie.
     *
     * <p>This endpoint is deliberately NON-IDEMPOTENT. Replaying a consumed handle is
     * treated as a security incident: the entire token family is revoked immediately, a
     * critical SIEM event is published, and 401 is returned. Clients must not retry after
     * a 401 — they must redirect the user to re-authenticate.
     *
     * <p>The handle must arrive exclusively via the {@code refresh_token} cookie. Any
     * request that supplies the handle via body, header, or query parameter is rejected
     * with 401 without consulting the database.
     */
    @Operation(
        operationId = "refresh",
        summary     = "Rotate refresh token (non-idempotent)",
        description = "Exchanges the HttpOnly refresh cookie for a new 15-minute access token " +
                      "and a rotated cookie. Replaying a consumed handle immediately revokes the " +
                      "entire token family. Do NOT retry on 401.")
    @PostMapping(value = "/refresh", produces = "application/json")
    public ResponseEntity<?> refresh(
            HttpServletRequest httpRequest,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String rawHandle) {

        if (rawHandle == null || rawHandle.isBlank()) {
            return reauthRequiredResponse();
        }

        String clientIp = resolveClientIp(httpRequest);
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);

        RefreshTokenService.RotationResult result;
        try {
            result = refreshTokenService.rotate(
                    rawHandle,
                    clientIp,
                    userAgent != null ? userAgent : "");
        } catch (InvalidCredentialsException e) {
            return reauthRequiredResponse();
        }

        int expiresIn = (int) ChronoUnit.SECONDS.between(
                Instant.now(), result.tokens().accessTokenExp());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.tokens().refreshHandle()).toString())
                .body(RefreshResponse.of(result.tokens().accessToken(), expiresIn));
    }

    // ---- Helpers ---------------------------------------------------------------

    private ResponseCookie refreshCookie(String handle) {
        return ResponseCookie
                .from(REFRESH_COOKIE_NAME, handle)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(REFRESH_MAX_AGE_SECS)
                .build();
    }

    private ResponseEntity<ApiErrorResponse> reauthRequiredResponse() {
        // Clears the cookie so the browser stops retrying the compromised handle
        ResponseCookie clearCookie = ResponseCookie
                .from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();

        String traceId = resolveTraceId();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(ApiErrorResponse.of(ErrorCode.REAUTHENTICATION_REQUIRED,
                        "Reauthentication required.", traceId));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
