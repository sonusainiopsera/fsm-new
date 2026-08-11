package com.fieldservice.app.error;

import com.fieldservice.platform.api.*;
import com.fieldservice.platform.validation.AllowedValues;
import com.fieldservice.platform.validation.SafeText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Test-only controller that triggers every exception type and validation failure
 * for use by {@link ErrorContractTest}. Never deployed in production.
 */
@RestController
@Validated
@RequestMapping("/test/error")
public class TestErrorController {

    // ── Typed exception endpoints ─────────────────────────────────────────

    @GetMapping("/not-found")
    public void notFound() {
        throw new NotFoundException("WorkOrder", "test-id-123");
    }

    @GetMapping("/forbidden")
    public void forbidden() {
        throw new ForbiddenException("Caller lacks required permission");
    }

    @GetMapping("/illegal-transition")
    public void illegalTransition() {
        throw new IllegalTransitionException("WorkOrder", "COMPLETED", "ASSIGN");
    }

    @GetMapping("/conflict")
    public void conflict() {
        throw new ConflictException("Resource already exists with this identifier");
    }

    @GetMapping("/business-guard")
    public void businessGuard() {
        throw new BusinessGuardException("CERT_REQUIRED", "Technician lacks required certification");
    }

    @GetMapping("/rate-limited")
    public void rateLimited() {
        throw new RateLimitedException(30L);
    }

    @GetMapping("/provider-degraded")
    public void providerDegraded() {
        throw new ProviderDegradedException("notification-service", "Connection refused");
    }

    @GetMapping("/internal-error")
    public void internalError() {
        throw new RuntimeException("Unexpected NPE in production logic");
    }

    // ── Validation endpoints ──────────────────────────────────────────────

    @PostMapping("/validate")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateRequest create(@RequestBody @Valid CreateRequest req) {
        return req;
    }

    // ── Request DTO ───────────────────────────────────────────────────────

    public record CreateRequest(
            @NotBlank(message = "Title is required")
            @SafeText(max = 300)
            String title,

            @NotBlank(message = "Priority is required")
            @AllowedValues(values = {"LOW", "MEDIUM", "HIGH", "CRITICAL"})
            String priority
    ) {}
}
