package com.fieldservice.sla;

import com.fieldservice.sla.web.SlaPolicyConstraintValidator;
import com.fieldservice.sla.web.SlaPolicyValid;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link SlaPolicyConstraintValidator}.
 * No Spring context — uses the Jakarta Validation bootstrap directly.
 */
class SlaPolicyConstraintValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void bootstrap() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -----------------------------------------------------------------------
    // Minimal stub record — mimics both create and update request shapes
    // -----------------------------------------------------------------------

    @SlaPolicyValid
    record SlaRequest(Integer responseMinutes, Integer resolutionMinutes) {
        public Integer getResponseMinutes()   { return responseMinutes; }
        public Integer getResolutionMinutes() { return resolutionMinutes; }
    }

    // -----------------------------------------------------------------------
    // Valid cases
    // -----------------------------------------------------------------------

    @Test
    void valid_when_resolution_equals_response() {
        Set<ConstraintViolation<SlaRequest>> violations =
                validator.validate(new SlaRequest(30, 30));
        assertThat(violations).isEmpty();
    }

    @Test
    void valid_when_resolution_greater_than_response() {
        Set<ConstraintViolation<SlaRequest>> violations =
                validator.validate(new SlaRequest(30, 60));
        assertThat(violations).isEmpty();
    }

    @Test
    void valid_when_response_is_null() {
        // Per-field @NotNull handles nulls; cross-field validator must not throw
        Set<ConstraintViolation<SlaRequest>> violations =
                validator.validate(new SlaRequest(null, 60));
        assertThat(violations).isEmpty();
    }

    @Test
    void valid_when_resolution_is_null() {
        Set<ConstraintViolation<SlaRequest>> violations =
                validator.validate(new SlaRequest(30, null));
        assertThat(violations).isEmpty();
    }

    @Test
    void valid_when_both_null() {
        Set<ConstraintViolation<SlaRequest>> violations =
                validator.validate(new SlaRequest(null, null));
        assertThat(violations).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Invalid cases
    // -----------------------------------------------------------------------

    @Test
    void invalid_when_resolution_less_than_response() {
        Set<ConstraintViolation<SlaRequest>> violations =
                validator.validate(new SlaRequest(60, 30));
        assertThat(violations).hasSize(1);
        ConstraintViolation<SlaRequest> v = violations.iterator().next();
        assertThat(v.getPropertyPath().toString()).isEqualTo("resolutionMinutes");
        assertThat(v.getMessage()).contains("resolutionMinutes must be >= responseMinutes");
        assertThat(v.getMessage()).contains("60");
    }

    @Test
    void invalid_message_includes_both_values() {
        Set<ConstraintViolation<SlaRequest>> violations =
                validator.validate(new SlaRequest(120, 15));
        assertThat(violations).hasSize(1);
        String msg = violations.iterator().next().getMessage();
        assertThat(msg).contains("120").contains("15");
    }

    // -----------------------------------------------------------------------
    // Direct validator test (no Bean Validation bootstrap)
    // -----------------------------------------------------------------------

    @Test
    void validator_is_valid_for_null_root_object() {
        SlaPolicyConstraintValidator sut = new SlaPolicyConstraintValidator();
        // ConstraintValidatorContext not needed when returning true — pass null
        assertThat(sut.isValid(null, null)).isTrue();
    }

    @Test
    void validator_ignores_object_without_getter_methods() {
        SlaPolicyConstraintValidator sut = new SlaPolicyConstraintValidator();
        // Plain Object has no getResponseMinutes() — should be treated as valid
        assertThat(sut.isValid("just-a-string", null)).isTrue();
    }
}
