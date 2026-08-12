package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for per-user notification channel preferences.
 *
 * <p>Resolution rule (default-on): when no explicit preference row exists for a
 * (user, category, channel) triple, the channel is treated as enabled. This guarantees
 * fan-out is never blocked by missing configuration.
 */
@Service
@Transactional(readOnly = true)
public class NotificationPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceService.class);

    static final Set<NotificationChannel> ALL_CHANNELS =
            EnumSet.allOf(NotificationChannel.class);

    private final NotificationPreferenceRepository repository;
    private final ScopedQueryExecutor scopedQueryExecutor;

    public NotificationPreferenceService(
            NotificationPreferenceRepository repository,
            ScopedQueryExecutor scopedQueryExecutor) {
        this.repository         = repository;
        this.scopedQueryExecutor = scopedQueryExecutor;
    }

    /**
     * Returns the effective channel set for the given user and category.
     *
     * <p>For each configured channel, an explicit row's {@code enabled} value takes precedence;
     * absence of a row means enabled (default-on). Callers in fan-out consumers should catch
     * any exception and fall back to {@link #ALL_CHANNELS}.
     */
    public Set<NotificationChannel> resolveEffective(UUID userId, String category) {
        Set<NotificationChannel> enabled = EnumSet.noneOf(NotificationChannel.class);
        for (NotificationChannel channel : ALL_CHANNELS) {
            Optional<NotificationPreferenceEntity> row =
                    repository.findByKey(userId, category, channel.name());
            boolean channelEnabled = row.map(NotificationPreferenceEntity::isEnabled).orElse(true);
            if (channelEnabled) {
                enabled.add(channel);
            }
        }
        return enabled;
    }

    /**
     * Returns the effective preference page for the given user.
     * Only explicit rows are returned; DEFAULT semantics are indicated in the response DTO.
     */
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
    public PagedResponse<NotificationPreferenceResponse> getPreferences(
            UUID userId, PageQuery pageQuery, HttpServletRequest request) {

        Specification<NotificationPreferenceEntity> userFilter =
                (root, query, cb) -> cb.equal(root.get("userId"), userId);

        Sort sort = Sort.by(Sort.Direction.ASC, "category", "channel", "id");
        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);

        Page<NotificationPreferenceEntity> page =
                scopedQueryExecutor.findAll(
                        NotificationPreferenceEntity.class, userFilter, pageable, repository);

        List<NotificationPreferenceResponse> data = page.getContent().stream()
                .map(e -> new NotificationPreferenceResponse(
                        e.getCategory(), e.getChannel(), e.isEnabled(),
                        NotificationPreferenceResponse.Source.EXPLICIT))
                .toList();

        PageMeta meta   = PageMeta.of(page.getNumber(), page.getSize(), page.getTotalElements());
        PageLinks links = buildLinks(request, pageQuery.page(), pageQuery.size(), page.getTotalPages());
        return PagedResponse.of(data, meta, links);
    }

    /** Upserts explicit preferences for the given user and returns the updated view. */
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
    @Transactional
    public PagedResponse<NotificationPreferenceResponse> updatePreferences(
            UUID userId,
            NotificationPreferenceUpdateRequest updateRequest,
            HttpServletRequest request) {

        for (NotificationPreferenceUpdateRequest.PreferenceItem item : updateRequest.preferences()) {
            Optional<NotificationPreferenceEntity> existing =
                    repository.findByKey(userId, item.category(), item.channel());
            if (existing.isPresent()) {
                existing.get().setEnabled(item.enabled());
                repository.save(existing.get());
            } else {
                repository.save(
                        NotificationPreferenceEntity.create(userId, item.category(),
                                item.channel(), item.enabled()));
            }
        }

        log.info("notification_preference_updated userId={} count={}",
                userId, updateRequest.preferences().size());

        return getPreferences(userId, new PageQuery(0, PageQuery.MAX_SIZE, List.of(), null), request);
    }

    private PageLinks buildLinks(HttpServletRequest request, int page, int size, int totalPages) {
        String base = UriComponentsBuilder.fromUriString(request.getRequestURI())
                .replaceQueryParam("size", size)
                .toUriString();
        String next = (page + 1 < totalPages)
                ? base + (base.contains("?") ? "&" : "?") + "page=" + (page + 1) : null;
        String prev = (page > 0)
                ? base + (base.contains("?") ? "&" : "?") + "page=" + (page - 1) : null;
        return PageLinks.of(next, prev);
    }
}
