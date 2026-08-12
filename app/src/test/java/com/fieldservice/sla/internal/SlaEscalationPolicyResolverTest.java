package com.fieldservice.sla.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaEscalationPolicyResolverTest {

    @Mock SlaEscalationPolicyRepository repository;

    private Clock fixedClock;
    private SlaEscalationPolicyResolver resolver;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2024-06-15T10:00:00Z"), ZoneOffset.UTC);
        resolver = new SlaEscalationPolicyResolver(repository, fixedClock);
    }

    @Test
    void resolve_withMatchingPolicy_returnsPolicy() {
        SlaEscalationPolicy policy = mockPolicy("SlaRiskFlagged", "HIGH");
        when(repository.findActivePolicy(eq("SlaRiskFlagged"), eq("HIGH"), any()))
                .thenReturn(Optional.of(policy));

        Optional<SlaEscalationPolicy> result = resolver.resolve("SlaRiskFlagged", "HIGH");

        assertThat(result).isPresent();
        assertThat(result.get().getEventType()).isEqualTo("SlaRiskFlagged");
        assertThat(result.get().getPriority()).isEqualTo("HIGH");
    }

    @Test
    void resolve_withNoPolicy_returnsEmpty() {
        when(repository.findActivePolicy(any(), any(), any())).thenReturn(Optional.empty());

        Optional<SlaEscalationPolicy> result = resolver.resolve("SlaRiskFlagged", "LOW");

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_cachesSecondCall() {
        SlaEscalationPolicy policy = mockPolicy("SlaRiskFlagged", "HIGH");
        when(repository.findActivePolicy(any(), any(), any())).thenReturn(Optional.of(policy));

        resolver.resolve("SlaRiskFlagged", "HIGH");
        resolver.resolve("SlaRiskFlagged", "HIGH");

        // Only one DB call due to cache
        verify(repository, times(1)).findActivePolicy(any(), any(), any());
    }

    @Test
    void invalidate_clearsCacheAndForcesReload() {
        SlaEscalationPolicy policy = mockPolicy("SlaRiskFlagged", "HIGH");
        when(repository.findActivePolicy(any(), any(), any())).thenReturn(Optional.of(policy));

        resolver.resolve("SlaRiskFlagged", "HIGH");
        resolver.invalidate();
        resolver.resolve("SlaRiskFlagged", "HIGH");

        verify(repository, times(2)).findActivePolicy(any(), any(), any());
    }

    @Test
    void resolve_differentKeys_cachedIndependently() {
        when(repository.findActivePolicy(eq("SlaRiskFlagged"), eq("HIGH"), any()))
                .thenReturn(Optional.of(mockPolicy("SlaRiskFlagged", "HIGH")));
        when(repository.findActivePolicy(eq("SlaBreached"), eq("CRITICAL"), any()))
                .thenReturn(Optional.of(mockPolicy("SlaBreached", "CRITICAL")));

        resolver.resolve("SlaRiskFlagged", "HIGH");
        resolver.resolve("SlaBreached", "CRITICAL");
        resolver.resolve("SlaRiskFlagged", "HIGH");

        verify(repository, times(1)).findActivePolicy(eq("SlaRiskFlagged"), eq("HIGH"), any());
        verify(repository, times(1)).findActivePolicy(eq("SlaBreached"), eq("CRITICAL"), any());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static SlaEscalationPolicy mockPolicy(String eventType, String priority) {
        SlaEscalationPolicy policy = mock(SlaEscalationPolicy.class);
        when(policy.getEventType()).thenReturn(eventType);
        when(policy.getPriority()).thenReturn(priority);
        when(policy.getRecipientRoles()).thenReturn(new String[]{"DISPATCHER"});
        when(policy.getChannels()).thenReturn(new String[]{"EMAIL"});
        when(policy.getManagerGraceMinutes()).thenReturn(30);
        when(policy.getQuietHoursStart()).thenReturn(null);
        when(policy.getQuietHoursEnd()).thenReturn(null);
        when(policy.getQuietHoursZone()).thenReturn("UTC");
        return policy;
    }
}
