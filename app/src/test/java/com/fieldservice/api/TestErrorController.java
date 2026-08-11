package com.fieldservice.api;

import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.ForbiddenException;
import com.fieldservice.platform.exception.IllegalTransitionException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.exception.ProviderDegradedException;
import com.fieldservice.platform.exception.RateLimitedException;
import com.fieldservice.platform.validation.AllowedValues;
import com.fieldservice.platform.validation.SafeText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only REST controller that exposes endpoints which throw each domain exception.
 *
 * <p>Used exclusively by {@link ErrorContractTest} to verify that the
 * {@link GlobalExceptionHandler} maps each exception to the correct HTTP status,
 * error code, and response shape. Not loaded in production contexts.
 */
@TestComponent
@RestController
@RequestMapping("/test/errors")
@Profile("test")
public class TestErrorController {

    @GetMapping("/not-found")
    public void notFound() {
        throw new NotFoundException("WorkOrder", "woid-123");
    }

    @GetMapping("/forbidden")
    public void forbidden() {
        throw new ForbiddenException();
    }

    @GetMapping("/illegal-transition")
    public void illegalTransition() {
        throw new IllegalTransitionException("OPEN", "CLOSED");
    }

    @GetMapping("/conflict")
    public void conflict() {
        throw new ConflictException("Duplicate external reference.");
    }

    @GetMapping("/business-guard")
    public void businessGuard() {
        throw new BusinessGuardException("CapacityGuard", "Technician at maximum daily capacity.");
    }

    @GetMapping("/rate-limited")
    public void rateLimited() {
        throw new RateLimitedException(60L);
    }

    @GetMapping("/provider-degraded")
    public void providerDegraded() {
        throw new ProviderDegradedException("TravelTimeService", "upstream timeout");
    }

    @GetMapping("/internal-error")
    public void internalError() {
        throw new RuntimeException("Simulated unexpected failure.");
    }

    @PostMapping("/validated")
    public void validated(@Valid @RequestBody ValidatedPayload payload) {
        // Intentionally empty — validation triggers the response
    }

    public record ValidatedPayload(
            @NotBlank(message = "name is required")
            @SafeText(maxLength = 50)
            String name,

            @AllowedValues({"LOW", "MEDIUM", "HIGH", "CRITICAL"})
            String priority
    ) {}
}
