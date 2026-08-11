package com.fieldservice.platform.pagination;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Generic, scope-aware pagination service.
 *
 * <h3>Strategy selection</h3>
 * <ul>
 *   <li>Offset mode: {@code cursor} is absent and {@code page <= keysetOffsetThreshold}</li>
 *   <li>Keyset mode: {@code cursor} is present — the cursor defines the starting position</li>
 *   <li>Auto-switch: when the next offset page would cross the threshold AND the entity
 *       implements {@link KeysetAware}, the {@code next} link embeds a keyset cursor
 *       instead of an incremented page number</li>
 * </ul>
 *
 * <h3>Security</h3>
 * All queries are scoped through {@link ScopedQueryExecutor}, so out-of-scope rows
 * never appear in data or totals.
 */
@Service
public class SpecificationPageService {

    private final ScopedQueryExecutor scopedQueryExecutor;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Value("${fieldservice.pagination.keyset-offset-threshold:20}")
    private int keysetOffsetThreshold;

    @Value("${fieldservice.pagination.cursor-hmac-secret:changeme-not-for-production}")
    private String cursorHmacSecret;

    public SpecificationPageService(ScopedQueryExecutor scopedQueryExecutor) {
        this.scopedQueryExecutor = scopedQueryExecutor;
    }

    /**
     * Returns a page of {@code entityType} results, applying scope, optional filter,
     * sort (validated against {@code sortAllowList}), and pagination.
     *
     * @param executor      the entity's JPA repository
     * @param entityType    runtime class of T (needed by ScopedQueryExecutor)
     * @param filterSpec    optional additional filter (may be {@code null})
     * @param query         parsed, clamped page parameters
     * @param sortAllowList allowed sort fields for this resource
     * @param resourceName  used to tag the Micrometer timer
     * @param httpRequest   current HTTP request (for link generation)
     * @param <T>           entity type
     * @return paged response with metadata and navigation links
     */
    public <T extends ScopedEntity> PagedResponse<T> findPage(
            JpaSpecificationExecutor<T> executor,
            Class<T> entityType,
            @Nullable Specification<T> filterSpec,
            PageQuery query,
            SortAllowList sortAllowList,
            String resourceName,
            HttpServletRequest httpRequest) {

        Sort sort = buildSort(query, sortAllowList);
        boolean keysetMode = (query.cursor() != null);

        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        try {
            PagedResponse<T> result = keysetMode
                    ? doKeysetPage(executor, entityType, filterSpec, query, sort, httpRequest)
                    : doOffsetPage(executor, entityType, filterSpec, query, sort, httpRequest);
            return result;
        } finally {
            if (sample != null) {
                sample.stop(Timer.builder("fieldservice.pagination.query")
                        .tag("resource", resourceName)
                        .tag("mode", keysetMode ? "keyset" : "offset")
                        .register(meterRegistry));
            }
        }
    }

    // ── Offset pagination ─────────────────────────────────────────────────────

    private <T extends ScopedEntity> PagedResponse<T> doOffsetPage(
            JpaSpecificationExecutor<T> executor,
            Class<T> entityType,
            @Nullable Specification<T> filterSpec,
            PageQuery query,
            Sort sort,
            HttpServletRequest httpRequest) {

        Pageable pageable = PageRequest.of(query.page(), query.size(), sort);
        Page<T> page = scopedQueryExecutor.findAll(executor, filterSpec, pageable, entityType);

        PageMeta meta = new PageMeta(
                page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), false);

        PageLinks links = buildOffsetLinks(httpRequest, page, query, sort);
        return new PagedResponse<>(page.getContent(), meta, links);
    }

    private <T> PageLinks buildOffsetLinks(HttpServletRequest request,
                                            Page<T> page,
                                            PageQuery query,
                                            Sort sort) {
        String prev = null;
        String next = null;

        if (page.hasPrevious()) {
            prev = buildOffsetUrl(request, page.getNumber() - 1, page.getSize(), query.sort());
        }

        if (page.hasNext()) {
            int nextPage = page.getNumber() + 1;
            // Auto-switch: embed a keyset cursor when crossing the threshold
            if (nextPage >= keysetOffsetThreshold && !page.isEmpty()) {
                T last = page.getContent().get(page.getContent().size() - 1);
                if (last instanceof KeysetAware keysetAware) {
                    String dir = deriveSortDirection(sort);
                    byte[] secret = cursorHmacSecret.getBytes(StandardCharsets.UTF_8);
                    KeysetCursor cursor = new KeysetCursor(
                            keysetAware.getCreatedAt(), keysetAware.getId(), dir);
                    next = buildCursorUrl(request, cursor.encode(secret),
                            page.getSize(), query.sort());
                } else {
                    next = buildOffsetUrl(request, nextPage, page.getSize(), query.sort());
                }
            } else {
                next = buildOffsetUrl(request, nextPage, page.getSize(), query.sort());
            }
        }

        return new PageLinks(next, prev);
    }

    // ── Keyset pagination ─────────────────────────────────────────────────────

    private <T extends ScopedEntity> PagedResponse<T> doKeysetPage(
            JpaSpecificationExecutor<T> executor,
            Class<T> entityType,
            @Nullable Specification<T> filterSpec,
            PageQuery query,
            Sort sort,
            HttpServletRequest httpRequest) {

        byte[] secret = cursorHmacSecret.getBytes(StandardCharsets.UTF_8);
        String direction = deriveSortDirection(sort);
        KeysetCursor cursor = KeysetCursor.decode(query.cursor(), secret, direction);

        Specification<T> keysetSpec = buildKeysetSpec(cursor, direction);
        Specification<T> combined = filterSpec != null
                ? filterSpec.and(keysetSpec)
                : keysetSpec;

        Pageable pageable = PageRequest.of(0, query.size(), sort);
        Page<T> page = scopedQueryExecutor.findAll(executor, combined, pageable, entityType);

        // In keyset mode, exact count is not computed (-1 = unavailable, estimated = true)
        PageMeta meta = new PageMeta(0, page.getSize(), -1L, -1, true);

        PageLinks links = buildKeysetLinks(httpRequest, page, query, sort, secret, direction);
        return new PagedResponse<>(page.getContent(), meta, links);
    }

    private <T> PageLinks buildKeysetLinks(HttpServletRequest request,
                                            Page<T> page,
                                            PageQuery query,
                                            Sort sort,
                                            byte[] secret,
                                            String direction) {
        // Keyset navigation is forward-only: prev is always null
        String next = null;

        if (!page.isEmpty() && page.hasNext()) {
            T last = page.getContent().get(page.getContent().size() - 1);
            if (last instanceof KeysetAware keysetAware) {
                KeysetCursor nextCursor = new KeysetCursor(
                        keysetAware.getCreatedAt(), keysetAware.getId(), direction);
                next = buildCursorUrl(request, nextCursor.encode(secret),
                        page.getSize(), query.sort());
            }
        }

        return new PageLinks(next, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> Specification<T> buildKeysetSpec(KeysetCursor cursor, String direction) {
        return (root, q, cb) -> {
            // id tie-break: use native UUID comparison (consistent with ORDER BY id ASC)
            var eqCreatedAt = cb.equal(root.get("createdAt"), cursor.createdAt());
            var gtId = cb.greaterThan((jakarta.persistence.criteria.Expression<UUID>) root.get("id"),
                    cursor.id());
            if ("ASC".equals(direction)) {
                // Next rows: created_at > cursor OR (created_at = cursor AND id > cursorId)
                var gtCreatedAt = cb.greaterThan(root.get("createdAt"), cursor.createdAt());
                return cb.or(gtCreatedAt, cb.and(eqCreatedAt, gtId));
            } else {
                // DESC: next rows: created_at < cursor OR (created_at = cursor AND id > cursorId)
                var ltCreatedAt = cb.lessThan(root.get("createdAt"), cursor.createdAt());
                return cb.or(ltCreatedAt, cb.and(eqCreatedAt, gtId));
            }
        };
    }

    // ── Sort building ─────────────────────────────────────────────────────────

    private static Sort buildSort(PageQuery query, SortAllowList sortAllowList) {
        Sort.Direction direction = Sort.Direction.DESC;
        String sortField = "createdAt";

        if (query.sort() != null && !query.sort().isBlank()) {
            String[] parts = query.sort().split(",", 2);
            sortField = sortAllowList.resolve(parts[0].trim());  // throws InvalidSortException
            if (parts.length > 1) {
                String dir = parts[1].trim().toUpperCase();
                if ("ASC".equals(dir)) {
                    direction = Sort.Direction.ASC;
                }
            }
        }

        if ("id".equals(sortField)) {
            return Sort.by(new Sort.Order(direction, "id"));
        }
        return Sort.by(new Sort.Order(direction, sortField), Sort.Order.asc("id"));
    }

    private static String deriveSortDirection(Sort sort) {
        Sort.Order createdAtOrder = sort.getOrderFor("createdAt");
        if (createdAtOrder != null) {
            return createdAtOrder.getDirection().name();
        }
        return "DESC";
    }

    // ── URL builders ──────────────────────────────────────────────────────────

    private static String buildOffsetUrl(HttpServletRequest request,
                                          int page, int size,
                                          @Nullable String sort) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromHttpRequest(new ServletServerHttpRequest(request))
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .replaceQueryParam("cursor");  // remove cursor param if present
        if (sort != null) {
            b.replaceQueryParam("sort", sort);
        }
        return b.build().toUriString();
    }

    private static String buildCursorUrl(HttpServletRequest request,
                                          String cursor, int size,
                                          @Nullable String sort) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromHttpRequest(new ServletServerHttpRequest(request))
                .replaceQueryParam("cursor", cursor)
                .replaceQueryParam("size", size)
                .replaceQueryParam("page");  // remove page param in keyset mode
        if (sort != null) {
            b.replaceQueryParam("sort", sort);
        }
        return b.build().toUriString();
    }

    // ── Visible for testing ───────────────────────────────────────────────────

    static Sort buildSortForTest(PageQuery query, SortAllowList sortAllowList) {
        return buildSort(query, sortAllowList);
    }

    /** Returns a copy of the sort-derived keyset spec, for use in integration-test EXPLAIN plans. */
    public static <T> Specification<T> keysetSpecFor(KeysetCursor cursor) {
        return buildKeysetSpec(cursor, cursor.direction());
    }

    /** Exposes the configured HMAC secret (bytes) for integration tests. */
    public byte[] getCursorHmacSecretBytes() {
        return cursorHmacSecret.getBytes(StandardCharsets.UTF_8);
    }
}
