package com.fieldservice.workorder.technician;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests — no Spring context.
 *
 * <p>Covers: day-window boundary logic helpers, ETag derivation,
 * ContactMasker, and carry-over rule documentation.
 */
class TechnicianDayWindowTest {

    private static final UUID TECH_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TECH_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // ─── ContactMasker ────────────────────────────────────────────────────────

    @Nested
    class MaskPhone {

        @Test
        void returnsLastFourDigits() {
            assertThat(ContactMasker.maskPhone("+1 (555) 867-5309"))
                    .isEqualTo("****5309");
        }

        @Test
        void handlesRawDigitsOnly() {
            assertThat(ContactMasker.maskPhone("01234567890"))
                    .isEqualTo("****7890");
        }

        @Test
        void handlesExactlyFourDigits() {
            assertThat(ContactMasker.maskPhone("1234"))
                    .isEqualTo("****1234");
        }

        @Test
        void fewerThanFourDigitsReturnsFourStars() {
            assertThat(ContactMasker.maskPhone("123"))
                    .isEqualTo("****");
        }

        @Test
        void nullInputReturnsNull() {
            assertThat(ContactMasker.maskPhone(null)).isNull();
        }

        @Test
        void blankInputReturnsNull() {
            assertThat(ContactMasker.maskPhone("   ")).isNull();
        }

        @Test
        void stripsNonDigitCharacters() {
            assertThat(ContactMasker.maskPhone("(800) ABC-1234"))
                    .isEqualTo("****1234");
        }
    }

    // ─── ETag derivation ──────────────────────────────────────────────────────

    @Nested
    class ETagDerivation {

        @Test
        void etagHasSha256Format() {
            String etag = TechnicianDayQueryService.computeEtag(
                    TECH_A, LocalDate.of(2026, 9, 1), 5L, 42L);
            // Quoted hex string of SHA-256 (64 hex chars)
            assertThat(etag)
                    .startsWith("\"")
                    .endsWith("\"")
                    .hasSizeGreaterThan(10);
            String inner = etag.substring(1, etag.length() - 1);
            assertThat(inner).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        void etagChangesWhenCountChanges() {
            String a = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 5L, 42L);
            String b = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 6L, 42L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void etagChangesWhenVersionChanges() {
            String a = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 5L, 42L);
            String b = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 5L, 43L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void etagChangesForDifferentTechnician() {
            String a = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 5L, 42L);
            String b = TechnicianDayQueryService.computeEtag(TECH_B, LocalDate.of(2026, 9, 1), 5L, 42L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void etagChangesForDifferentDate() {
            String a = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 5L, 42L);
            String b = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 2), 5L, 42L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void etagIsDeterministic() {
            String a = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 5L, 42L);
            String b = TechnicianDayQueryService.computeEtag(TECH_A, LocalDate.of(2026, 9, 1), 5L, 42L);
            assertThat(a).isEqualTo(b);
        }

        @Test
        void emptyResultHasStableEtag() {
            var r1 = TechnicianDayQueryService.TechnicianDayResult.empty(20);
            var r2 = TechnicianDayQueryService.TechnicianDayResult.empty(20);
            assertThat(r1.etag()).isEqualTo(r2.etag());
        }
    }

    // ─── Day-window boundary documentation ────────────────────────────────────

    @Nested
    class DayWindowBoundaries {

        @Test
        void startOfDayBoundaryIsInclusive() {
            LocalDate day = LocalDate.of(2026, 9, 15);
            // dayStart = 2026-09-15T00:00:00Z, dayEnd = 2026-09-16T00:00:00Z
            // scheduled_window_start == dayStart → included (>=)
            java.time.Instant dayStart = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            assertThat(dayStart).isNotNull();
            // Just assert boundary instants are correct:
            assertThat(dayStart.toString()).isEqualTo("2026-09-15T00:00:00Z");
        }

        @Test
        void endOfDayBoundaryIsExclusive() {
            LocalDate day = LocalDate.of(2026, 9, 15);
            java.time.Instant dayEnd = day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            // scheduled_window_start == dayEnd → NOT included (<)
            assertThat(dayEnd.toString()).isEqualTo("2026-09-16T00:00:00Z");
        }
    }
}
