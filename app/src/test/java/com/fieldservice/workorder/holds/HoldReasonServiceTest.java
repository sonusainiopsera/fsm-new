package com.fieldservice.workorder.holds;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HoldReasonService} vocabulary validation and cache fallback.
 */
class HoldReasonServiceTest {

    HoldReasonRepository repo;
    HoldReasonService service;

    @BeforeEach
    void setUp() {
        repo = mock(HoldReasonRepository.class);
        // No Redis in unit tests — service always falls through to DB
        service = new HoldReasonService(repo, null, new ObjectMapper());

        HoldReason awaiting = holdReason("AWAITING_PARTS", "Awaiting Parts", true, 1);
        HoldReason weather  = holdReason("WEATHER",        "Weather",        true, 4);
        when(repo.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(awaiting, weather));
    }

    @Test
    @DisplayName("activeReasons returns only active codes in sort order")
    void activeReasons_returnsActive() {
        List<HoldReasonResponse> reasons = service.activeReasons();
        assertThat(reasons).extracting(HoldReasonResponse::code)
                .containsExactly("AWAITING_PARTS", "WEATHER");
    }

    @Test
    @DisplayName("validate: active code passes without exception")
    void validate_activeCode_passes() {
        service.validate("AWAITING_PARTS");
        // no exception
    }

    @Test
    @DisplayName("validate: unknown code throws HoldReasonValidationException")
    void validate_unknownCode_throws() {
        assertThatThrownBy(() -> service.validate("BOGUS_CODE"))
                .isInstanceOf(HoldReasonValidationException.class)
                .hasMessageContaining("BOGUS_CODE");
    }

    @Test
    @DisplayName("validate: inactive code is absent from activeReasons and throws")
    void validate_inactiveCode_throws() {
        // LEGACY_OTHER is not in active reasons returned by mock
        assertThatThrownBy(() -> service.validate("LEGACY_OTHER"))
                .isInstanceOf(HoldReasonValidationException.class);
    }

    @Test
    @DisplayName("validate: null code throws HoldReasonValidationException")
    void validate_nullCode_throws() {
        assertThatThrownBy(() -> service.validate(null))
                .isInstanceOf(HoldReasonValidationException.class);
    }

    // ---- helpers -----------------------------------------------------------

    private static HoldReason holdReason(String code, String label, boolean active, int order) {
        try {
            HoldReason hr = new HoldReason() {};
            // Use reflection to set fields on the @Immutable entity
            var codeField = HoldReason.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(hr, code);

            var labelField = HoldReason.class.getDeclaredField("label");
            labelField.setAccessible(true);
            labelField.set(hr, label);

            var activeField = HoldReason.class.getDeclaredField("active");
            activeField.setAccessible(true);
            activeField.set(hr, active);

            var sortField = HoldReason.class.getDeclaredField("sortOrder");
            sortField.setAccessible(true);
            sortField.set(hr, order);

            return hr;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
