package com.fieldservice.workorder.holds;

import com.fieldservice.domain.workorder.HoldReason;
import com.fieldservice.domain.workorder.HoldReasonRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class HoldReasonServiceTest {

    private HoldReasonRepository holdReasonRepository;
    private HoldReasonService service;

    @BeforeEach
    void setUp() {
        holdReasonRepository = mock(HoldReasonRepository.class);
        // No Redis in unit tests
        service = new HoldReasonService(holdReasonRepository, null, new ObjectMapper());

        when(holdReasonRepository.findByActiveTrueOrderBySortOrderAsc())
                .thenReturn(List.of(
                        holdReason("AWAITING_PARTS", "Awaiting parts", 10),
                        holdReason("WEATHER", "Weather conditions", 20)
                ));
    }

    @Test
    @DisplayName("getActiveReasons returns active reasons in sort order")
    void getActiveReasons_returnsActiveInOrder() {
        List<HoldReasonResponse> reasons = service.getActiveReasons();
        assertThat(reasons).hasSize(2);
        assertThat(reasons.get(0).code()).isEqualTo("AWAITING_PARTS");
        assertThat(reasons.get(1).code()).isEqualTo("WEATHER");
    }

    @Test
    @DisplayName("validate passes for a known active code")
    void validate_passesForActiveCode() {
        service.validate("AWAITING_PARTS");
        // no exception
    }

    @Test
    @DisplayName("validate throws InvalidHoldReasonCodeException for unknown code")
    void validate_throwsForUnknownCode() {
        assertThatThrownBy(() -> service.validate("NO_SUCH_CODE"))
                .isInstanceOf(InvalidHoldReasonCodeException.class)
                .hasMessageContaining("NO_SUCH_CODE");
    }

    @Test
    @DisplayName("validate treats inactive codes as absent (DB returns only active)")
    void validate_treatsInactiveAsAbsent() {
        // Repository only returns active; inactive codes are not in the set
        assertThatThrownBy(() -> service.validate("INACTIVE_CODE"))
                .isInstanceOf(InvalidHoldReasonCodeException.class);
    }

    @Test
    @DisplayName("validate with null code throws InvalidHoldReasonCodeException")
    void validate_throwsForNullCode() {
        // null is not in the active set
        assertThatThrownBy(() -> service.validate(null))
                .isInstanceOf(InvalidHoldReasonCodeException.class);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private HoldReason holdReason(String code, String label, int sortOrder) {
        HoldReason r = mock(HoldReason.class);
        when(r.getCode()).thenReturn(code);
        when(r.getLabel()).thenReturn(label);
        when(r.getSortOrder()).thenReturn(sortOrder);
        when(r.isActive()).thenReturn(true);
        return r;
    }
}
