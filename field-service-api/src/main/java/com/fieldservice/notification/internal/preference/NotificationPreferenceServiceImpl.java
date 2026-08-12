package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.api.NotificationPreferenceService;
import com.fieldservice.notification.domain.NotificationCategory;
import com.fieldservice.notification.domain.NotificationChannel;
import com.fieldservice.notification.web.dto.NotificationPreferenceDto;
import com.fieldservice.notification.web.dto.NotificationPreferenceDto.PreferenceSource;
import com.fieldservice.notification.web.dto.UpsertPreferencesRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link NotificationPreferenceService}.
 *
 * <p>Internal to the notification module — external modules must use the
 * {@link NotificationPreferenceService} interface only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;

    // ── resolveEffective ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Implements default-on semantics:
     * <ul>
     *   <li>No rows found → return all channels (user has never configured preferences)</li>
     *   <li>Rows found → return only channels where {@code enabled = true}</li>
     * </ul>
     *
     * <p>Any exception (e.g. database unavailability) is caught and logged; the
     * method falls back to returning all channels to avoid blocking notification
     * delivery.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<NotificationChannel> resolveEffective(UUID userId, NotificationCategory category) {
        try {
            List<NotificationPreference> prefs = repository.findAllByUserIdAndCategory(userId, category);

            if (prefs.isEmpty()) {
                // No explicit settings → default-on: all channels active
                log.debug("No explicit preferences for user={} category={}, defaulting to all channels",
                        userId, category);
                return EnumSet.allOf(NotificationChannel.class);
            }

            Set<NotificationChannel> active = EnumSet.noneOf(NotificationChannel.class);
            for (NotificationPreference pref : prefs) {
                if (pref.isEnabled()) {
                    active.add(pref.getChannel());
                }
            }
            // For channels with no explicit row, apply default-on
            for (NotificationChannel channel : NotificationChannel.values()) {
                boolean hasExplicitRow = prefs.stream()
                        .anyMatch(p -> p.getChannel() == channel);
                if (!hasExplicitRow) {
                    active.add(channel);
                }
            }
            return active;

        } catch (Exception ex) {
            log.warn("Failed to resolve notification preferences for user={} category={}, " +
                    "defaulting to all channels. Error: {}", userId, category, ex.getMessage(), ex);
            return EnumSet.allOf(NotificationChannel.class);
        }
    }

    // ── getPreferences ───────────────────────────────────────────────────────

    /**
     * Returns the full effective preference set for the user — all (category, channel)
     * combinations, each marked DEFAULT or EXPLICIT based on whether an explicit row exists.
     *
     * <p>This satisfies AC-1: "explicitly marking each entry as DEFAULT or EXPLICIT so a
     * caller can distinguish an unset default-on entry from a stored choice."
     */
    @Override
    @Transactional(readOnly = true)
    public Page<NotificationPreferenceDto> getPreferences(UUID userId, UUID callerId,
                                                          boolean isAdmin, Pageable pageable) {
        enforceScope(userId, callerId, isAdmin);

        // Fetch all explicit rows for this user
        Map<String, NotificationPreference> explicitByKey = repository
                .findAllByUserId(userId)
                .stream()
                .collect(Collectors.toMap(
                        p -> compositeKey(p.getCategory(), p.getChannel()),
                        p -> p
                ));

        // Build the full effective set: every (category, channel) combo
        List<NotificationPreferenceDto> all = buildEffectiveSet(explicitByKey);

        // Apply stable ordering: category asc, channel asc
        all.sort((a, b) -> {
            int catCmp = a.category().name().compareTo(b.category().name());
            return catCmp != 0 ? catCmp : a.channel().name().compareTo(b.channel().name());
        });

        return toPage(all, pageable);
    }

    // ── upsertPreferences ────────────────────────────────────────────────────

    @Override
    @Transactional
    public Page<NotificationPreferenceDto> upsertPreferences(UUID userId, UUID callerId,
                                                             boolean isAdmin,
                                                             UpsertPreferencesRequest request,
                                                             Pageable pageable) {
        enforceScope(userId, callerId, isAdmin);

        for (UpsertPreferencesRequest.PreferenceItem item : request.preferences()) {
            NotificationPreference pref = repository
                    .findByUserIdAndCategoryAndChannel(userId, item.category(), item.channel())
                    .orElseGet(() -> NotificationPreference.create(
                            userId, item.category(), item.channel(), item.enabled()));
            // For existing records, update only the enabled flag
            pref.setEnabled(item.enabled());
            repository.save(pref);
        }

        // Return the full updated effective preference list for this user after flush
        Map<String, NotificationPreference> explicitByKey = repository
                .findAllByUserId(userId)
                .stream()
                .collect(Collectors.toMap(
                        p -> compositeKey(p.getCategory(), p.getChannel()),
                        p -> p
                ));

        List<NotificationPreferenceDto> all = buildEffectiveSet(explicitByKey);
        all.sort((a, b) -> {
            int catCmp = a.category().name().compareTo(b.category().name());
            return catCmp != 0 ? catCmp : a.channel().name().compareTo(b.channel().name());
        });

        return toPage(all, pageable);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build the complete effective preference set: one DTO per (category, channel) pair.
     * Rows with an explicit entry are marked EXPLICIT; all others use default-on (enabled=true, DEFAULT).
     */
    private List<NotificationPreferenceDto> buildEffectiveSet(
            Map<String, NotificationPreference> explicitByKey) {

        List<NotificationPreferenceDto> result = new ArrayList<>();

        for (NotificationCategory category : NotificationCategory.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                String key = compositeKey(category, channel);
                NotificationPreference explicit = explicitByKey.get(key);

                if (explicit != null) {
                    result.add(new NotificationPreferenceDto(
                            category, channel, explicit.isEnabled(), PreferenceSource.EXPLICIT));
                } else {
                    // Default-on: no row means the channel is active
                    result.add(new NotificationPreferenceDto(
                            category, channel, true, PreferenceSource.DEFAULT));
                }
            }
        }
        return result;
    }

    private String compositeKey(NotificationCategory category, NotificationChannel channel) {
        return category.name() + ":" + channel.name();
    }

    /**
     * Enforce caller-is-self-or-admin scope.
     *
     * <p>Throws {@link AccessDeniedException} (→ HTTP 403) for any scope violation.
     * The message is intentionally generic to avoid leaking user existence.
     */
    private void enforceScope(UUID userId, UUID callerId, boolean isAdmin) {
        if (!isAdmin && (callerId == null || !callerId.equals(userId))) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /**
     * Convert a plain in-memory list to a Spring Data {@link Page} respecting the given
     * {@link Pageable}. Used because the full effective set is derived in-memory from
     * explicit rows and computed defaults rather than a direct query.
     */
    private Page<NotificationPreferenceDto> toPage(List<NotificationPreferenceDto> all,
                                                    Pageable pageable) {
        int total = all.size();
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), total);

        List<NotificationPreferenceDto> content = (start >= total)
                ? List.of()
                : all.subList(start, end);

        return new PageImpl<>(content, pageable, total);
    }
}
