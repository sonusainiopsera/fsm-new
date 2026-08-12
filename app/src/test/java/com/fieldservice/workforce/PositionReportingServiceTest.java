package com.fieldservice.workforce;

import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.repository.TechnicianRepository;
import com.fieldservice.workforce.internal.NoActiveJobException;
import com.fieldservice.workforce.internal.PositionRateLimitedException;
import com.fieldservice.workforce.internal.PositionReportingService;
import com.fieldservice.workforce.internal.PositionValidationException;
import com.fieldservice.workforce.internal.TechnicianPositionCacheGateway;
import com.fieldservice.workforce.internal.TechnicianPositionRepository;
import com.fieldservice.workforce.web.PositionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PositionReportingServiceTest {

    @Mock TechnicianRepository          technicianRepo;
    @Mock TechnicianPositionRepository  positionRepo;
    @Mock TechnicianPositionCacheGateway cacheGateway;
    @Mock JdbcTemplate                  jdbc;

    private PositionReportingService service;

    private static final UUID USER_ID       = UUID.randomUUID();
    private static final UUID TECHNICIAN_ID = UUID.randomUUID();
    private static final int  RETENTION     = 90;

    @BeforeEach
    void setUp() {
        service = new PositionReportingService(
                technicianRepo, positionRepo, cacheGateway, jdbc, RETENTION);
    }

    // ── Technician not found ───────────────────────────────────────────────────

    @Test
    @DisplayName("throws NotFoundException when no technician profile exists for userId")
    void technicianNotFound() {
        when(technicianRepo.findByUserId(USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reportPosition(USER_ID, validRequest()))
                .isInstanceOf(NotFoundException.class);
        verify(positionRepo, never()).save(any());
    }

    // ── Freshness validation ───────────────────────────────────────────────────

    @Test
    @DisplayName("rejects capturedAt older than 5 minutes")
    void rejectsStaleTimestamp() {
        stubTechnician();
        Instant stale = Instant.now().minus(6, ChronoUnit.MINUTES);
        assertThatThrownBy(() -> service.reportPosition(USER_ID,
                new PositionRequest(lat(51.5), lon(-0.1), 15, stale)))
                .isInstanceOf(PositionValidationException.class)
                .satisfies(ex -> {
                    var errors = ((PositionValidationException) ex).getFieldErrors();
                    assertThat(errors).anyMatch(e -> e.field().equals("capturedAt"));
                });
        verify(positionRepo, never()).save(any());
    }

    @Test
    @DisplayName("rejects capturedAt in the future")
    void rejectsFutureTimestamp() {
        stubTechnician();
        Instant future = Instant.now().plus(1, ChronoUnit.MINUTES);
        assertThatThrownBy(() -> service.reportPosition(USER_ID,
                new PositionRequest(lat(51.5), lon(-0.1), 15, future)))
                .isInstanceOf(PositionValidationException.class);
        verify(positionRepo, never()).save(any());
    }

    @Test
    @DisplayName("accepts capturedAt within the 5-minute window")
    void acceptsFreshTimestamp() {
        stubTechnician();
        stubActiveJob();
        when(cacheGateway.tryAcquireRateLimit(TECHNICIAN_ID)).thenReturn(true);
        when(positionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Instant fresh = Instant.now().minus(2, ChronoUnit.MINUTES);
        service.reportPosition(USER_ID, new PositionRequest(lat(51.5), lon(-0.1), 15, fresh));

        verify(positionRepo).save(any());
    }

    // ── Purpose check ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("rejects when technician has no active EN_ROUTE or IN_PROGRESS job")
    void rejectsNoActiveJob() {
        stubTechnician();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TECHNICIAN_ID))).thenReturn(0);
        assertThatThrownBy(() -> service.reportPosition(USER_ID, validRequest()))
                .isInstanceOf(NoActiveJobException.class);
        verify(positionRepo, never()).save(any());
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("rejects when rate limit window is active")
    void rejectsWhenRateLimited() {
        stubTechnician();
        stubActiveJob();
        when(cacheGateway.tryAcquireRateLimit(TECHNICIAN_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.reportPosition(USER_ID, validRequest()))
                .isInstanceOf(PositionRateLimitedException.class)
                .satisfies(ex ->
                    assertThat(((PositionRateLimitedException) ex).getRetryAfterSeconds()).isEqualTo(30));
        verify(positionRepo, never()).save(any());
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("accepts valid report: writes cache and persists row")
    void acceptsValidReport() {
        stubTechnician();
        stubActiveJob();
        when(cacheGateway.tryAcquireRateLimit(TECHNICIAN_ID)).thenReturn(true);
        when(positionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reportPosition(USER_ID, validRequest());

        verify(cacheGateway).writeCachedPosition(eq(TECHNICIAN_ID), any());
        verify(positionRepo).save(any());
    }

    @Test
    @DisplayName("PositionRateLimitedException returns 30-second retry-after")
    void rateLimitedRetryAfter() {
        var ex = new PositionRateLimitedException();
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(30);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubTechnician() {
        Technician tech = new Technician(USER_ID, "EMP-001", "Test Tech", "UTC");
        // Override the auto-generated id with our fixed TECHNICIAN_ID via reflection
        try {
            java.lang.reflect.Field idField = Technician.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(tech, TECHNICIAN_ID);
        } catch (Exception e) {
            throw new RuntimeException("Could not set Technician.id in test", e);
        }
        when(technicianRepo.findByUserId(USER_ID)).thenReturn(Optional.of(tech));
    }

    private void stubActiveJob() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TECHNICIAN_ID))).thenReturn(1);
    }

    private PositionRequest validRequest() {
        return new PositionRequest(lat(51.5074), lon(-0.1278), 20,
                Instant.now().minusSeconds(10));
    }

    private static BigDecimal lat(double v) { return BigDecimal.valueOf(v); }
    private static BigDecimal lon(double v) { return BigDecimal.valueOf(v); }
}
