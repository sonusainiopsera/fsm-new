package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPreferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements per-user notification channel preference resolution.
 *
 * <p>Default-on rule: when a user has no explicit preference row for a category,
 * all channels are treated as enabled so fan-out is never blocked by missing
 * configuration (BR-44).
 *
 * <p>Mutations are {@code @Transactional} so every upsert and the Envers audit
 * revision commit atomically.
 */
@Service
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceServiceImpl.class);

    private final NotificationPreferenceRepository repository;

    public NotificationPreferenceServiceImpl(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default-on: if the user has no rows for this category, returns all channels.
     * If the user has explicit rows, returns only the channels where {@code enabled=true}.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<NotificationChannel> resolveEffective(UUID userId, String category) {
        List<NotificationPreferenceEntity> rows =
                repository.findByUserIdAndCategory(userId, category);

        if (rows.isEmpty()) {
            return ALL_CHANNELS;
        }

        return rows.stream()
                .filter(NotificationPreferenceEntity::isEnabled)
                .map(NotificationPreferenceEntity::getChannel)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationChannel.class)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PreferenceEntryView> listPreferences(UUID userId) {
        List<NotificationPreferenceEntity> rows = repository.findByUserId(userId);
        return rows.stream()
                .map(e -> new PreferenceEntryView(
                        e.getCategory(),
                        e.getChannel(),
                        e.isEnabled(),
                        PreferenceSource.EXPLICIT))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<PreferenceEntryView> upsertPreferences(UUID userId,
                                                        List<PreferenceEntry> entries,
                                                        UUID actorId) {
        // Build a map from (category, channel) → entity for existing rows
        List<NotificationPreferenceEntity> existing = repository.findByUserId(userId);
        Map<String, NotificationPreferenceEntity> byKey = new HashMap<>();
        for (NotificationPreferenceEntity e : existing) {
            byKey.put(key(e.getCategory(), e.getChannel()), e);
        }

        List<NotificationPreferenceEntity> toSave = new ArrayList<>();

        for (PreferenceEntry entry : entries) {
            String k = key(entry.category(), entry.channel());
            NotificationPreferenceEntity entity = byKey.get(k);
            if (entity == null) {
                entity = NotificationPreferenceEntity.create(
                        userId, entry.category(), entry.channel(), entry.enabled());
            } else {
                entity.setEnabled(entry.enabled());
            }
            toSave.add(entity);
        }

        repository.saveAll(toSave);

        log.info("notification_preferences_upserted actor_id={} target_user_id={} count={}",
                actorId, userId, toSave.size());

        // Return the full updated list
        return listPreferences(userId);
    }

    private static String key(String category, NotificationChannel channel) {
        return category + ":" + channel.name();
    }
}
