package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.api.AssignmentService.CertificationGuardException;
import com.fieldservice.dispatch.api.EligibilityDataException;
import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentGuardTest {

    @Mock
    private EligibilityService eligibilityService;

    private AssignmentGuard guard;

    private static final UUID TECH_ID = UUID.randomUUID();
    private static final WorkOrderRequirements REQUIREMENTS = new WorkOrderRequirements(
            Set.of("ELEC-L2"),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            51.5, -0.1,
            250.0
    );

    @BeforeEach
    void setUp() {
        guard = new AssignmentGuard(eligibilityService);
    }

    @Test
    void passes_when_technician_in_eligible_set() {
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(TECH_ID), List.of(), 0));

        assertThatCode(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .doesNotThrowAnyException();
    }

    @Test
    void throws_with_CERTIFICATION_MISSING_when_cert_absent() {
        ExcludedCandidate excluded = new ExcludedCandidate(TECH_ID, ExclusionReason.CERTIFICATION_MISSING);
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(), List.of(excluded), 0));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(CertificationGuardException.class)
                .satisfies(ex -> {
                    CertificationGuardException cge = (CertificationGuardException) ex;
                    assertThatCode(() -> {
                        if (!"CERTIFICATION_MISSING".equals(cge.getCode()))
                            throw new AssertionError("Expected CERTIFICATION_MISSING, got " + cge.getCode());
                    }).doesNotThrowAnyException();
                });
    }

    @Test
    void throws_with_CERTIFICATION_EXPIRED_when_cert_expired() {
        ExcludedCandidate excluded = new ExcludedCandidate(TECH_ID, ExclusionReason.CERTIFICATION_EXPIRED);
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(), List.of(excluded), 0));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(CertificationGuardException.class)
                .satisfies(ex -> {
                    String code = ((CertificationGuardException) ex).getCode();
                    org.assertj.core.api.Assertions.assertThat(code).isEqualTo("CERTIFICATION_EXPIRED");
                });
    }

    @Test
    void throws_with_TECHNICIAN_INACTIVE_when_technician_is_inactive() {
        ExcludedCandidate excluded = new ExcludedCandidate(TECH_ID, ExclusionReason.INACTIVE_TECHNICIAN);
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(), List.of(excluded), 0));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(CertificationGuardException.class)
                .satisfies(ex -> {
                    String code = ((CertificationGuardException) ex).getCode();
                    org.assertj.core.api.Assertions.assertThat(code).isEqualTo("TECHNICIAN_INACTIVE");
                });
    }

    @Test
    void throws_with_TECHNICIAN_UNAVAILABLE_when_technician_unavailable_in_window() {
        ExcludedCandidate excluded = new ExcludedCandidate(TECH_ID, ExclusionReason.UNAVAILABLE_IN_WINDOW);
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(), List.of(excluded), 0));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(CertificationGuardException.class)
                .satisfies(ex -> {
                    String code = ((CertificationGuardException) ex).getCode();
                    org.assertj.core.api.Assertions.assertThat(code).isEqualTo("TECHNICIAN_UNAVAILABLE");
                });
    }

    @Test
    void throws_with_TECHNICIAN_OUT_OF_REACH_when_out_of_radius() {
        ExcludedCandidate excluded = new ExcludedCandidate(TECH_ID, ExclusionReason.OUT_OF_REACH);
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(), List.of(excluded), 0));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(CertificationGuardException.class)
                .satisfies(ex -> {
                    String code = ((CertificationGuardException) ex).getCode();
                    org.assertj.core.api.Assertions.assertThat(code).isEqualTo("TECHNICIAN_OUT_OF_REACH");
                });
    }

    @Test
    void falls_back_to_TECHNICIAN_INELIGIBLE_when_tech_absent_from_excluded_list() {
        UUID otherTech = UUID.randomUUID();
        ExcludedCandidate excluded = new ExcludedCandidate(otherTech, ExclusionReason.CERTIFICATION_MISSING);
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(), List.of(excluded), 0));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(CertificationGuardException.class)
                .satisfies(ex -> {
                    String code = ((CertificationGuardException) ex).getCode();
                    org.assertj.core.api.Assertions.assertThat(code).isEqualTo("TECHNICIAN_INELIGIBLE");
                });
    }

    @Test
    void propagates_EligibilityDataException_without_wrapping() {
        when(eligibilityService.evaluate(any()))
                .thenThrow(new EligibilityDataException("data source unavailable"));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(EligibilityDataException.class);
    }

    @Test
    void does_not_pass_when_different_technician_is_eligible() {
        UUID otherTech = UUID.randomUUID();
        when(eligibilityService.evaluate(any()))
                .thenReturn(new EligibilityResult(List.of(otherTech), List.of(), 0));

        assertThatThrownBy(() -> guard.evaluate(TECH_ID, REQUIREMENTS))
                .isInstanceOf(CertificationGuardException.class);
    }
}
