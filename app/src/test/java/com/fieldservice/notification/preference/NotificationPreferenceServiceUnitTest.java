package com.fieldservice.notification.preference;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.internal.preference.NotificationPreferenceEntity;
import com.fieldservice.notification.internal.preference.NotificationPreferenceRepository;
import com.fieldservice.notification.internal.preference.NotificationPreferenceService;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link NotificationPreferenceService#resolveEffective}.
 *
 * <p>No Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceUnitTest {

    @Mock NotificationPreferenceRepository repository;
    @Mock ScopedQueryExecutor scopedQueryExecutor;

    private NotificationPreferenceService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String CATEGORY = "WO_ASSIGNED";

    @BeforeEach
    void setUp() {
        service = new NotificationPreferenceService(repository, scopedQueryExecutor);
    }

    @Test
    void defaultOnWhenNoRowsExist() {
        when(repository.findByKey(eq(USER_ID), eq(CATEGORY), any())).thenReturn(Optional.empty());

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).containsExactlyInAnyOrderElementsOf(NotificationPreferenceService.ALL_CHANNELS);
    }

    @Test
    void explicitOptOutSuppressesExactlyOneChannel() {
        // EMAIL disabled, all others absent (default-on)
        when(repository.findByKey(USER_ID, CATEGORY, "EMAIL"))
                .thenReturn(Optional.of(disabledRow(USER_ID, CATEGORY, "EMAIL")));
        when(repository.findByKey(eq(USER_ID), eq(CATEGORY), argNotEq("EMAIL")))
                .thenReturn(Optional.empty());

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).doesNotContain(NotificationChannel.EMAIL);
        assertThat(result).contains(NotificationChannel.IN_APP, NotificationChannel.SMS,
                NotificationChannel.PUSH);
    }

    @Test
    void explicitEnableTrueRowStillIncludesChannel() {
        when(repository.findByKey(USER_ID, CATEGORY, "IN_APP"))
                .thenReturn(Optional.of(enabledRow(USER_ID, CATEGORY, "IN_APP")));
        when(repository.findByKey(eq(USER_ID), eq(CATEGORY), argNotEq("IN_APP")))
                .thenReturn(Optional.empty());

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).contains(NotificationChannel.IN_APP);
    }

    @Test
    void allChannelsDisabledReturnsEmptySet() {
        for (NotificationChannel ch : NotificationChannel.values()) {
            when(repository.findByKey(USER_ID, CATEGORY, ch.name()))
                    .thenReturn(Optional.of(disabledRow(USER_ID, CATEGORY, ch.name())));
        }

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).isEmpty();
    }

    @Test
    void perChannelGranularityIsIndependent() {
        when(repository.findByKey(USER_ID, CATEGORY, "SMS"))
                .thenReturn(Optional.of(disabledRow(USER_ID, CATEGORY, "SMS")));
        when(repository.findByKey(USER_ID, CATEGORY, "PUSH"))
                .thenReturn(Optional.of(disabledRow(USER_ID, CATEGORY, "PUSH")));
        when(repository.findByKey(eq(USER_ID), eq(CATEGORY), eq("EMAIL")))
                .thenReturn(Optional.empty());
        when(repository.findByKey(eq(USER_ID), eq(CATEGORY), eq("IN_APP")))
                .thenReturn(Optional.empty());

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static NotificationPreferenceEntity disabledRow(UUID userId, String category, String channel) {
        return NotificationPreferenceEntity.create(userId, category, channel, false);
    }

    private static NotificationPreferenceEntity enabledRow(UUID userId, String category, String channel) {
        return NotificationPreferenceEntity.create(userId, category, channel, true);
    }

    /** Mockito ArgumentMatcher helper — matches any string NOT equal to the given value. */
    private static String argNotEq(String excluded) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && !s.equals(excluded));
    }
}
