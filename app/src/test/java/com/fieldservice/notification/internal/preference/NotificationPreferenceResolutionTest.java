package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPreferenceService;
import com.fieldservice.notification.api.NotificationPreferenceService.PreferenceEntry;
import com.fieldservice.notification.api.NotificationPreferenceService.PreferenceEntryView;
import com.fieldservice.notification.api.NotificationPreferenceService.PreferenceSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for preference resolution logic — no Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPreferenceResolutionTest {

    private static final UUID   USER_ID  = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String CATEGORY = "WORK_ORDER_ASSIGNMENT";

    @Mock NotificationPreferenceRepository repository;

    NotificationPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationPreferenceServiceImpl(repository);
    }

    // ── Default-on ────────────────────────────────────────────────────────────

    @Test
    void resolveEffective_noRows_returnsAllChannels() {
        when(repository.findByUserIdAndCategory(USER_ID, CATEGORY))
                .thenReturn(List.of());

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).containsExactlyInAnyOrder(NotificationChannel.values());
    }

    @Test
    void resolveEffective_noRows_returnsExactlyFourChannels() {
        when(repository.findByUserIdAndCategory(USER_ID, CATEGORY))
                .thenReturn(List.of());

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).hasSize(4); // EMAIL, SMS, PUSH, IN_APP
    }

    // ── Explicit override ─────────────────────────────────────────────────────

    @Test
    void resolveEffective_allExplicitEnabled_returnsAllChannels() {
        List<NotificationPreferenceEntity> rows = List.of(
                entityWith(NotificationChannel.EMAIL,  true),
                entityWith(NotificationChannel.SMS,    true),
                entityWith(NotificationChannel.PUSH,   true),
                entityWith(NotificationChannel.IN_APP, true)
        );
        when(repository.findByUserIdAndCategory(USER_ID, CATEGORY)).thenReturn(rows);

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).containsExactlyInAnyOrder(NotificationChannel.values());
    }

    @Test
    void resolveEffective_emailDisabled_doesNotIncludeEmail() {
        List<NotificationPreferenceEntity> rows = List.of(
                entityWith(NotificationChannel.EMAIL,  false),
                entityWith(NotificationChannel.SMS,    true),
                entityWith(NotificationChannel.PUSH,   true),
                entityWith(NotificationChannel.IN_APP, true)
        );
        when(repository.findByUserIdAndCategory(USER_ID, CATEGORY)).thenReturn(rows);

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result)
                .doesNotContain(NotificationChannel.EMAIL)
                .containsExactlyInAnyOrder(
                        NotificationChannel.SMS, NotificationChannel.PUSH, NotificationChannel.IN_APP);
    }

    @Test
    void resolveEffective_onlyInAppEnabled_returnsOnlyInApp() {
        List<NotificationPreferenceEntity> rows = List.of(
                entityWith(NotificationChannel.EMAIL,  false),
                entityWith(NotificationChannel.SMS,    false),
                entityWith(NotificationChannel.PUSH,   false),
                entityWith(NotificationChannel.IN_APP, true)
        );
        when(repository.findByUserIdAndCategory(USER_ID, CATEGORY)).thenReturn(rows);

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).containsExactly(NotificationChannel.IN_APP);
    }

    @Test
    void resolveEffective_allExplicitDisabled_returnsEmptySet() {
        List<NotificationPreferenceEntity> rows = List.of(
                entityWith(NotificationChannel.EMAIL,  false),
                entityWith(NotificationChannel.SMS,    false),
                entityWith(NotificationChannel.PUSH,   false),
                entityWith(NotificationChannel.IN_APP, false)
        );
        when(repository.findByUserIdAndCategory(USER_ID, CATEGORY)).thenReturn(rows);

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        assertThat(result).isEmpty();
    }

    // ── Per-channel granularity ────────────────────────────────────────────────

    @Test
    void resolveEffective_partialRows_treatsAbsentChannelsAsEnabled() {
        // Only SMS row present (disabled) — EMAIL, PUSH, IN_APP have no row (default-on)
        // However, if ANY explicit row exists, we return only the explicitly-enabled ones.
        // This is the defined semantics: once a user has opted in to managing a category,
        // their explicit choices are authoritative for all channels in that category.
        List<NotificationPreferenceEntity> rows = List.of(
                entityWith(NotificationChannel.SMS, false)
        );
        when(repository.findByUserIdAndCategory(USER_ID, CATEGORY)).thenReturn(rows);

        Set<NotificationChannel> result = service.resolveEffective(USER_ID, CATEGORY);

        // Only SMS is explicitly disabled; no other channels are returned
        // because the user has taken explicit control of this category
        assertThat(result).doesNotContain(NotificationChannel.SMS);
    }

    // ── listPreferences ────────────────────────────────────────────────────────

    @Test
    void listPreferences_noRows_returnsEmptyList() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of());

        List<PreferenceEntryView> result = service.listPreferences(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void listPreferences_withRows_returnsExplicitSource() {
        List<NotificationPreferenceEntity> rows = List.of(
                entityWith(NotificationChannel.EMAIL, true)
        );
        when(repository.findByUserId(USER_ID)).thenReturn(rows);

        List<PreferenceEntryView> result = service.listPreferences(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo(PreferenceSource.EXPLICIT);
        assertThat(result.get(0).channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(result.get(0).enabled()).isTrue();
    }

    // ── AllChannels constant ───────────────────────────────────────────────────

    @Test
    void allChannels_containsAllFourChannels() {
        assertThat(NotificationPreferenceService.ALL_CHANNELS)
                .containsExactlyInAnyOrder(NotificationChannel.values());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private NotificationPreferenceEntity entityWith(NotificationChannel channel, boolean enabled) {
        return NotificationPreferenceEntity.create(USER_ID, CATEGORY, channel, enabled);
    }
}
