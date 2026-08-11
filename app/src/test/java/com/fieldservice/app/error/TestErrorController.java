package com.fieldservice.app.error;

import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.api.exception.IllegalTransitionException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.api.exception.ProviderDegradedException;
import com.fieldservice.platform.api.exception.RateLimitedException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller that triggers each exception type and validation failure mode.
 * Active only under the "test" profile to avoid exposure in production.
 */
@RestController
@RequestMapping("/test/errors")
@Profile("test")
public class TestErrorController {

    public record SimpleRequest(@NotBlank String name) {}

    @GetMapping("/not-found")
    public void throwNotFound() {
        throw new NotFoundException("Widget", "abc-123");
    }

    @GetMapping("/forbidden")
    public void throwForbidden() {
        throw new ForbiddenException("Access denied to resource.");
    }

    @GetMapping("/scoped-denied")
    public void throwScopedDenied() {
        throw new ScopedAccessDeniedException("work_order", "Scoped access denied.");
    }

    @GetMapping("/illegal-transition")
    public void throwIllegalTransition() {
        throw new IllegalTransitionException("COMPLETED", "IN_PROGRESS");
    }

    @GetMapping("/conflict")
    public void throwConflict() {
        throw new ConflictException("Duplicate entity detected.");
    }

    @GetMapping("/optimistic-lock")
    public void throwOptimisticLock() {
        throw new ObjectOptimisticLockingFailureException(Object.class, "lock-conflict");
    }

    @GetMapping("/data-integrity")
    public void throwDataIntegrity() {
        throw new DataIntegrityViolationException("constraint violation raw text");
    }

    @GetMapping("/business-guard")
    public void throwBusinessGuard() {
        throw new BusinessGuardException("Cannot schedule past-due work order.");
    }

    @GetMapping("/rate-limited")
    public void throwRateLimited() {
        throw new RateLimitedException(60);
    }

    @GetMapping("/provider-degraded")
    public void throwProviderDegraded() {
        throw new ProviderDegradedException("payment-gateway");
    }

    @GetMapping("/unhandled")
    public void throwUnhandled() {
        throw new RuntimeException("Unexpected internal failure");
    }

    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void validateBody(@Valid @RequestBody SimpleRequest request) {
        // returns 200 on valid input; validation errors surface as 400
    }
}
