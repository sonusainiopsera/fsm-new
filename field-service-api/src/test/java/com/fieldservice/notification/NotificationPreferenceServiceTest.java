package com.fieldservice.notification;

import com.fieldservice.notification.domain.NotificationCategory;
import com.fieldservice.notification.domain.NotificationChannel;
import com.fieldservice.notification.internal.preference.NotificationPreference;
import com.fieldservice.notification.internal.preference.NotificationPreferenceRepository;
import com.fieldservice.notification.internal.preference.NotificationPreferenceServiceImpl;
import com.fieldservice.notification.web.dto.NotificationPreferenceDto;
import com.fieldservice.notification.web.dto.NotificationPreferenceDto.PreferenceSource;
import com.fieldservice.notification.web.dto.UpsertPreferencesRequest;
import com.fieldservice.notification.web.dto.UpsertPreferencesRequest.PreferenceItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pure unit tests for {@link NotificationPreferenceServiceImpl}.
 * No Spring context — uses Mockito only.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock
    private NotificationPreferenceRepository repository;

    @InjectMocks
    private NotificationPreferenceServiceImpl service;

    private static final UUID USER_ID   = UUID.fromString("a0000000-0000-0000-0000-000000000004");
    private static final UUID CALLER_ID = USER_ID;
    private static final UUID OTHER_ID  = UUID.fromString("a0000000-0000-0000-0000-000000000099");

    // ── resolveEffective ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveEffective")
    class ResolveEffective {

        @Test
        @DisplayName("returns ALL channels when no explicit preferences exist (default-on)")
        void noPreferences_returnsAllChannels() {
            given(repository.findAllByUserIdAndCategory(USER_ID, NotificationCategory.WORK_ORDER_CREATED))
                    .willReturn(List.of());

            Set<NotificationChannel> result =
                    service.resolveEffective(USER_ID, NotificationCategory.WORK_ORDER_CREATED);

            assertThat(result).containsExactlyInAnyOrderElementsOf(
                    EnumSet.allOf(NotificationChannel.class));
        }

        @Test
        @DisplayName("returns only enabled channels when all preferences are explicit")
        void allChannelsExplicit_returnsOnlyEnabled() {
            List<NotificationPreference> prefs = List.of(
                    buildPref(NotificationChannel.EMAIL,  true),
                    buildPref(NotificationChannel.SMS,    false),
                    buildPref(NotificationChannel.IN_APP, true),
                    buildPref(NotificationChannel.PUSH,   false)
            );
            given(repository.findAllByUserIdAndCategory(USER_ID, NotificationCategory.WORK_ORDER_ASSIGNED))
                    .willReturn(prefs);

            Set<NotificationChannel> result =
                    service.resolveEffective(USER_ID, NotificationCategory.WORK_ORDER_ASSIGNED);

            assertThat(result)
                    .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
        }

        @Test
        @DisplayName("mixed explicit preferences: absent channels are not returned (only stored rows matter)")
        void partialExplicit_returnsOnlyExplicitlyEnabled() {
            // Only EMAIL (enabled) and SMS (disabled) are stored; IN_APP and PUSH are absent.
            // resolveEffective only returns channels with stored enabled=true.
            List<NotificationPreference> prefs = List.of(
                    buildPref(NotificationChannel.EMAIL, true),
                    buildPref(NotificationChannel.SMS,   false)
            );
            given(repository.findAllByUserIdAndCategory(USER_ID, NotificationCategory.SLA_BREACH))
                    .willReturn(prefs);

            Set<NotificationChannel> result =
                    service.resolveEffective(USER_ID, NotificationCategory.SLA_BREACH);

            assertThat(result).containsExactly(NotificationChannel.EMAIL);
        }

        @Test
        @DisplayName("all channels disabled → returns empty set")
        void allDisabled_returnsEmptySet() {
            List<NotificationPreference> prefs = List.of(
                    buildPref(NotificationChannel.EMAIL,  false),
                    buildPref(NotificationChannel.SMS,    false),
                    buildPref(NotificationChannel.IN_APP, false),
                    buildPref(NotificationChannel.PUSH,   false)
            );
            given(repository.findAllByUserIdAndCategory(USER_ID, NotificationCategory.SLA_BREACH))
                    .willReturn(prefs);

            Set<NotificationChannel> result =
                    service.resolveEffective(USER_ID, NotificationCategory.SLA_BREACH);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns ALL channels on repository exception (fail-safe default)")
        void repositoryException_defaultsToAllChannels() {
            given(repository.findAllByUserIdAndCategory(any(), any()))
                    .willThrow(new RuntimeException("DB unavailable"));

            Set<NotificationChannel> result =
                    service.resolveEffective(USER_ID, NotificationCategory.WORK_ORDER_CREATED);

            assertThat(result).containsExactlyInAnyOrderElementsOf(
                    EnumSet.allOf(NotificationChannel.class));
        }
    }

    // ── getPreferences ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getPreferences")
    class GetPreferences {

        @Test
        @DisplayName("self-access returns paged DTOs with EXPLICIT source")
        void selfAccess_returnsDtos() {
            Pageable pageable = PageRequest.of(0, 20);
            NotificationPreference pref = buildPref(NotificationChannel.EMAIL, true);
            given(repository.findAllByUserId(USER_ID, pageable))
                    .willReturn(new PageImpl<>(List.of(pref)));

            var result = service.getPreferences(USER_ID, CALLER_ID, false, pageable);

            assertThat(result.getContent()).hasSize(1);
            NotificationPreferenceDto dto = result.getContent().get(0);
            assertThat(dto.channel()).isEqualTo(NotificationChannel.EMAIL);
            assertThat(dto.enabled()).isTrue();
            assertThat(dto.source()).isEqualTo(PreferenceSource.EXPLICIT);
        }

        @Test
        @DisplayName("admin can access any user's preferences")
        void adminAccess_allowed() {
            Pageable pageable = PageRequest.of(0, 20);
            given(repository.findAllByUserId(USER_ID, pageable))
                    .willReturn(new PageImpl<>(List.of()));

            // Admin caller with a different callerId — should not throw
            var result = service.getPreferences(USER_ID, OTHER_ID, true, pageable);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cross-user access (non-admin) throws AccessDeniedException")
        void crossUserAccess_throwsAccessDenied() {
            assertThatThrownBy(() ->
                    service.getPreferences(USER_ID, OTHER_ID, false, PageRequest.of(0, 20)))
                    .isInstanceOf(AccessDeniedException.class);

            verify(repository, never()).findAllByUserId(any(), any());
        }
    }

    // ── upsertPreferences ────────────────────────────────────────────────────

    @Nested
    @DisplayName("upsertPreferences")
    class UpsertPreferences {

        @Test
        @DisplayName("creates new preference when none exists")
        void create_whenNotExists() {
            Pageable pageable = PageRequest.of(0, 20);
            var request = new UpsertPreferencesRequest(List.of(
                    new PreferenceItem(NotificationCategory.WORK_ORDER_CREATED, NotificationChannel.EMAIL, false)
            ));

            given(repository.findByUserIdAndCategoryAndChannel(
                    USER_ID, NotificationCategory.WORK_ORDER_CREATED, NotificationChannel.EMAIL))
                    .willReturn(Optional.empty());
            given(repository.findAllByUserId(eq(USER_ID), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of()));

            service.upsertPreferences(USER_ID, CALLER_ID, false, request, pageable);

            ArgumentCaptor<NotificationPreference> captor =
                    ArgumentCaptor.forClass(NotificationPreference.class);
            verify(repository, times(1)).save(captor.capture());

            NotificationPreference saved = captor.getValue();
            assertThat(saved.getCategory()).isEqualTo(NotificationCategory.WORK_ORDER_CREATED);
            assertThat(saved.getChannel()).isEqualTo(NotificationChannel.EMAIL);
            assertThat(saved.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("updates existing preference enabled flag")
        void update_whenExists() {
            Pageable pageable = PageRequest.of(0, 20);
            NotificationPreference existing = buildPref(NotificationChannel.SMS, true);
            var request = new UpsertPreferencesRequest(List.of(
                    new PreferenceItem(NotificationCategory.SLA_AT_RISK, NotificationChannel.SMS, false)
            ));

            given(repository.findByUserIdAndCategoryAndChannel(
                    USER_ID, NotificationCategory.SLA_AT_RISK, NotificationChannel.SMS))
                    .willReturn(Optional.of(existing));
            given(repository.findAllByUserId(eq(USER_ID), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of(existing)));

            service.upsertPreferences(USER_ID, CALLER_ID, false, request, pageable);

            verify(repository).save(existing);
            assertThat(existing.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("cross-user upsert (non-admin) throws AccessDeniedException")
        void crossUserUpsert_throwsAccessDenied() {
            var request = new UpsertPreferencesRequest(List.of(
                    new PreferenceItem(NotificationCategory.WORK_ORDER_CREATED, NotificationChannel.PUSH, true)
            ));

            assertThatThrownBy(() ->
                    service.upsertPreferences(USER_ID, OTHER_ID, false, request, PageRequest.of(0, 20)))
                    .isInstanceOf(AccessDeniedException.class);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("multiple items in one request are all saved")
        void multipleItems_allSaved() {
            Pageable pageable = PageRequest.of(0, 20);
            var request = new UpsertPreferencesRequest(List.of(
                    new PreferenceItem(NotificationCategory.WORK_ORDER_CREATED, NotificationChannel.EMAIL,  true),
                    new PreferenceItem(NotificationCategory.WORK_ORDER_CREATED, NotificationChannel.SMS,    false),
                    new PreferenceItem(NotificationCategory.WORK_ORDER_CREATED, NotificationChannel.IN_APP, true)
            ));

            given(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                    .willReturn(Optional.empty());
            given(repository.findAllByUserId(eq(USER_ID), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of()));

            service.upsertPreferences(USER_ID, CALLER_ID, false, request, pageable);

            verify(repository, times(3)).save(any(NotificationPreference.class));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private NotificationPreference buildPref(NotificationChannel channel, boolean enabled) {
        return NotificationPreference.create(
                USER_ID,
                NotificationCategory.WORK_ORDER_ASSIGNED,
                channel,
                enabled
        );
    }
}
