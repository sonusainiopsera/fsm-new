package com.fieldservice.identity.api;

import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.identity.api.dto.LoginResponse;
import com.fieldservice.identity.application.LoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Authentication endpoint.
 *
 * <p>POST /api/v1/auth/login accepts {@link LoginRequest}, validates credentials through
 * {@link LoginService}, and returns an RS256 access token in the response body plus a
 * refresh handle in an HttpOnly Secure SameSite=Strict cookie.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Authentication and token management")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME  = "refresh_token";
    private static final String REFRESH_COOKIE_PATH  = "/api/v1/auth";
    private static final long   REFRESH_MAX_AGE_SECS = Duration.ofDays(7).getSeconds();

    private final LoginService loginService;

    public AuthController(LoginService loginService) {
        this.loginService = loginService;
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

        ResponseCookie refreshCookie = ResponseCookie
                .from(REFRESH_COOKIE_NAME, result.tokens().refreshHandle())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(REFRESH_MAX_AGE_SECS)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(body);
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
