package com.fieldservice.analytics.internal.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CohortMaturityResolver} (WO-164, AC-3, AC-8).
 *
 * <p>Uses an injected fixed {@link Clock} to assert promotion at the exact 30-day boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CohortMaturityResolver unit tests")
class CohortMaturityResolverTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("AC-3: closed 30 days ago → MATURED at exactly the 30-day instant")
    void resolveMaturity_exactlyAtBoundary_matured() {
        Instant closedAt = Instant.parse("2025-05-01T12:00:00Z");
        Instant maturedAt = closedAt.plus(Duration.ofDays(30)); // exactly 30 days later

        // Clock is set to exactly matured_at
        Clock atBoundary = Clock.fixed(maturedAt, ZoneOffset.UTC);
        CohortMaturityResolver resolver = new CohortMaturityResolver(jdbcTemplate, atBoundary);

        assertThat(resolver.resolveMaturity(closedAt)).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("AC-3: closed 29 days ago → PROVISIONAL (window still open)")
    void resolveMaturity_oneSecondBeforeBoundary_provisional() {
        Instant closedAt = Instant.parse("2025-05-01T12:00:00Z");
        Instant maturedAt = closedAt.plus(Duration.ofDays(30));
        Instant oneSecondBefore = maturedAt.minus(Duration.ofSeconds(1));

        Clock justBefore = Clock.fixed(oneSecondBefore, ZoneOffset.UTC);
        CohortMaturityResolver resolver = new CohortMaturityResolver(jdbcTemplate, justBefore);

        assertThat(resolver.resolveMaturity(closedAt)).isEqualTo("PROVISIONAL");
    }

    @Test
    @DisplayName("AC-3: closed 31 days ago → MATURED (past boundary)")
    void resolveMaturity_pastBoundary_matured() {
        Instant closedAt = Instant.parse("2025-05-01T12:00:00Z");
        Instant oneSecondAfter = closedAt.plus(Duration.ofDays(30)).plus(Duration.ofSeconds(1));

        Clock pastBoundary = Clock.fixed(oneSecondAfter, ZoneOffset.UTC);
        CohortMaturityResolver resolver = new CohortMaturityResolver(jdbcTemplate, pastBoundary);

        assertThat(resolver.resolveMaturity(closedAt)).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("promoteMaturedRows delegates to an idempotent UPDATE")
    void promoteMaturedRows_delegatesToJdbc() {
        Clock clock = Clock.fixed(Instant.parse("2025-06-01T00:00:00Z"), ZoneOffset.UTC);
        CohortMaturityResolver resolver = new CohortMaturityResolver(jdbcTemplate, clock);

        when(jdbcTemplate.update(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(3);

        int promoted = resolver.promoteMaturedRows();

        assertThat(promoted).isEqualTo(3);
    }
}
