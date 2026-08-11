package com.fieldservice.identity.api;

import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.identity.api.dto.LoginResponse;
import com.fieldservice.identity.api.dto.RefreshResponse;
import com.fieldservice.identity.api.dto.StreamTicketResponse;
import com.fieldservice.identity.application.LoginAttemptTracker;
import com.fieldservice.identity.application.LoginService;
import com.fieldservice.identity.application.RefreshTokenService;
import com.fieldservice.identity.application.StreamTicketService;
import com.fieldservice.identity.token.StreamTicketStore;
import com.fieldservice.platform.api.ErrorEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Credential-based token issuance")
public class AuthController {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials.";
    private static final String REAUTH_REQUIRED_MESSAGE = "Reauthentication required.";
    private static final String AUTH_PATH = "/api/v1/auth";
    private static final String REFRESH_COOKIE_NAME = "refreshToken";

    /** 32 bytes base64url without padding = exactly 43 chars; [A-Za-z0-9_-] charset. */
    private static final Pattern HANDLE_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{43}$");

    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final StreamTicketService streamTicketService;
    private final long refreshTokenTtlSeconds;

    public AuthController(LoginService loginService,
                          RefreshTokenService refreshTokenService,
                          StreamTicketService streamTicketService,
                          com.fieldservice.identity.config.AuthProperties authProperties) {
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.streamTicketService = streamTicketService;
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

    /**
     * Silently renews an access token using the HttpOnly refresh cookie.
     *
     * <p><strong>Non-idempotent:</strong> each call rotates the refresh handle. Clients
     * must not blindly retry a 2xx response — doing so with the same handle will trigger
     * reuse detection and revoke the entire session family.
     *
     * <p>The endpoint is intentionally reachable while the access token is expired (see
     * {@code SecurityFilterChainConfig} permitted paths). It reads the handle exclusively
     * from the {@code refreshToken} HttpOnly cookie; a handle supplied via body, query
     * parameter or request header is ignored and the call fails with 401.
     *
     * @return 200 with new access token and rotated cookie, or 401 for any failure mode
     *         (missing cookie, malformed, unknown, consumed, revoked, expired, inactive user)
     */
    @Operation(
            operationId = "refresh",
            summary = "Silently rotate an access token using the HttpOnly refresh cookie",
            description = "Non-idempotent. Each successful call rotates the refresh handle. " +
                    "Presenting a previously used handle triggers immediate family revocation. " +
                    "All failure modes return 401 REAUTHENTICATION_REQUIRED.")
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> refresh(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String rawHandle,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String tid = traceId();
        httpResponse.setHeader("X-Trace-Id", tid);

        // Missing cookie
        if (rawHandle == null) {
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, buildClearRefreshCookie());
            return reauthResponse(tid);
        }

        // Malformed handle: reject before any DB lookup to avoid unnecessary I/O
        if (!HANDLE_PATTERN.matcher(rawHandle).matches()) {
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, buildClearRefreshCookie());
            return reauthResponse(tid);
        }

        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);

        RefreshTokenService.RotationResult result =
                refreshTokenService.rotate(rawHandle, tid, clientIp, userAgent);

        if (result instanceof RefreshTokenService.RotationResult.Success success) {
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(success.refreshHandle()));
            return ResponseEntity.ok(
                    new RefreshResponse(success.accessToken(), "Bearer", success.expiresIn()));
        }

        // All failure modes: clear the cookie and return uniform 401
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, buildClearRefreshCookie());
        return reauthResponse(tid);
    }

    // -----------------------------------------------------------------------
    // Stream ticket issuance
    // -----------------------------------------------------------------------

    /**
     * Exchanges a valid access token for a single-use, IP-bound SSE stream ticket.
     *
     * <p>The returned ticket is a 256-bit opaque value with a 60-second TTL. It is bound
     * to the authenticated user and the client IP observed at issuance, and is consumed
     * atomically on first use. Replayed or expired tickets are refused with 401.
     *
     * <p>The ticket must be passed as the {@code ticket} query parameter when opening the
     * SSE stream at {@code GET /api/v1/streams/alerts}. It must never be logged, shared,
     * or reused — the client must request a fresh ticket for each stream connection.
     *
     * @return 200 with {@link StreamTicketResponse}; 503 if the ticket store is unavailable
     */
    @Operation(
            operationId = "issueStreamTicket",
            summary = "Issue a single-use IP-bound SSE stream ticket",
            description = "Exchanges a valid access token for a 60-second single-use ticket " +
                    "for the SSE stream endpoint. Ticket values are never logged or persisted.")
    @PostMapping(value = "/stream-ticket", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> issueStreamTicket(HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        String tid = traceId();
        httpResponse.setHeader("X-Trace-Id", tid);

        String clientIp = extractClientIp(httpRequest);
        try {
            String ticketValue = streamTicketService.issueTicket(clientIp);
            return ResponseEntity.ok(new StreamTicketResponse(
                    ticketValue,
                    (int) StreamTicketService.TICKET_TTL.getSeconds()));
        } catch (StreamTicketStore.StoreUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorEnvelope(
                            ErrorEnvelope.Code.AUTH_DEPENDENCY_UNAVAILABLE,
                            "Authentication service is temporarily unavailable. Please try again shortly.",
                            tid, Instant.now()));
        }
    }

    // -----------------------------------------------------------------------
    // Cookie builders
    // -----------------------------------------------------------------------

    private String buildRefreshCookie(String refreshHandle) {
        return String.format(
                "%s=%s; HttpOnly; Secure; SameSite=Strict; Path=%s; Max-Age=%d",
                REFRESH_COOKIE_NAME, refreshHandle, AUTH_PATH, refreshTokenTtlSeconds);
    }

    private static String buildClearRefreshCookie() {
        return String.format(
                "%s=; HttpOnly; Secure; SameSite=Strict; Path=%s; Max-Age=0",
                REFRESH_COOKIE_NAME, AUTH_PATH);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ResponseEntity<ErrorEnvelope> reauthResponse(String tid) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.REAUTHENTICATION_REQUIRED,
                        REAUTH_REQUIRED_MESSAGE,
                        tid, Instant.now()));
    }

    private static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
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
