package com.fieldservice.sla.web.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the cross-field SLA validation constraint (WO-198, AC-3).
 *
 * <p>No Spring context — validates against the raw Bean Validation constraint.
 */
@DisplayName("SlaPolicyConstraintValidator unit tests")
class SlaPolicyConstraintValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // ── UpdateSlaPolicyRequest ────────────────────────────────────────────────

    @Test
    @DisplayName("valid request: resolutionMinutes == responseMinutes — passes")
    void update_resolutionEqualsResponse_valid() {
        UpdateSlaPolicyRequest req = new UpdateSlaPolicyRequest(60, 60, new BigDecimal("0.80"), false, 0);
        Set<ConstraintViolation<UpdateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("valid request: resolutionMinutes > responseMinutes — passes")
    void update_resolutionGtResponse_valid() {
        UpdateSlaPolicyRequest req = new UpdateSlaPolicyRequest(60, 120, new BigDecimal("0.80"), false, 0);
        Set<ConstraintViolation<UpdateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("invalid: resolutionMinutes < responseMinutes — cross-field violation on resolutionMinutes")
    void update_resolutionLtResponse_invalid() {
        UpdateSlaPolicyRequest req = new UpdateSlaPolicyRequest(120, 60, new BigDecimal("0.80"), false, 0);
        Set<ConstraintViolation<UpdateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("resolutionMinutes"));
    }

    @Test
    @DisplayName("invalid: responseMinutes <= 0 — field-level violation")
    void update_responseMinutesZero_invalid() {
        UpdateSlaPolicyRequest req = new UpdateSlaPolicyRequest(0, 60, new BigDecimal("0.80"), false, 0);
        Set<ConstraintViolation<UpdateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("responseMinutes"));
    }

    @Test
    @DisplayName("invalid: atRiskFraction < 0.50 — field-level violation")
    void update_atRiskFractionTooLow_invalid() {
        UpdateSlaPolicyRequest req = new UpdateSlaPolicyRequest(60, 120, new BigDecimal("0.49"), false, 0);
        Set<ConstraintViolation<UpdateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("atRiskFraction"));
    }

    @Test
    @DisplayName("valid: atRiskFraction == 1.00 — accepted (edge case)")
    void update_atRiskFractionExactlyOne_valid() {
        UpdateSlaPolicyRequest req = new UpdateSlaPolicyRequest(60, 120, new BigDecimal("1.00"), false, 0);
        Set<ConstraintViolation<UpdateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("invalid: resolutionMinutes null — @NotNull violation")
    void update_resolutionMinutesNull_invalid() {
        UpdateSlaPolicyRequest req = new UpdateSlaPolicyRequest(60, null, new BigDecimal("0.80"), false, 0);
        Set<ConstraintViolation<UpdateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("resolutionMinutes"));
    }

    // ── CreateSlaPolicyRequest ────────────────────────────────────────────────

    @Test
    @DisplayName("create: resolutionMinutes < responseMinutes — cross-field violation")
    void create_resolutionLtResponse_invalid() {
        CreateSlaPolicyRequest req = new CreateSlaPolicyRequest(
                "HIGH", 240, 60, new BigDecimal("0.80"), Instant.now());
        Set<ConstraintViolation<CreateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("resolutionMinutes"));
    }

    @Test
    @DisplayName("create: valid request passes all constraints")
    void create_valid() {
        CreateSlaPolicyRequest req = new CreateSlaPolicyRequest(
                "HIGH", 60, 240, new BigDecimal("0.80"), Instant.now());
        Set<ConstraintViolation<CreateSlaPolicyRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
