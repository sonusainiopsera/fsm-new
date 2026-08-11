package com.fieldservice.pagination;

import com.fieldservice.platform.pagination.KeysetCursor;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.pagination.SortField;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.persistence.ScopedRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generic pagination service that composes filter specifications with the AccessScope predicate,
 * applies allow-listed sorting with a mandatory {@code id} tie-break, and transparently
 * switches to keyset (cursor) pagination beyond the configurable page threshold.
 *
 * <p><strong>Sort injection defence:</strong> every sort field is resolved through the
 * caller-supplied {@link SortAllowList} before any query is built. Unknown client-supplied
 * identifiers are rejected with {@link InvalidSortException} → 400.
 *
 * <p><strong>Scope contract:</strong> delegates to {@link ScopedQueryExecutor} so the
 * AccessScope predicate is always composed before the query reaches the database.
 * {@code totalElements} in offset mode is derived from the scoped COUNT query and never
 * leaks rows outside the caller's scope.
 *
 * <p><strong>Keyset mode:</strong> automatically activated when {@code cursor} is present
 * or when {@code page > keysetThresholdPage}. In keyset mode {@code totalElements = -1} and
 * {@code estimated = true} are returned instead of running an expensive full-table COUNT.
 */
@Service
@Transactional(readOnly = true)
public class SpecificationPageService {

    /** Page number beyond which keyset mode is required. Default: 20. */
    @Value("${app.pagination.keyset.threshold:20}")
    private int keysetThresholdPage;

    /** HMAC signing secret for cursor integrity. Must be set to a strong random value in production. */
    @Value("${app.pagination.cursor.secret:dev-cursor-secret-change-in-prod!!}")
    private String cursorSecret;

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final MeterRegistry meterRegistry;

    public SpecificationPageService(
            ScopedQueryExecutor scopedQueryExecutor,
            MeterRegistry meterRegistry) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Executes a paginated scoped query and returns a {@link PagedResponse}.
     *
     * @param entityType entity class
     * @param filter     optional caller-supplied filter specification
     * @param pageQuery  parsed and validated request parameters
     * @param allowList  per-resource sort field allow-list
     * @param repo       scoped repository for the entity type
     * @param resource   resource name for Micrometer timer tag (e.g. {@code "work_order"})
     * @param request    current HTTP request for link generation
     * @param <T>        entity type
     * @return paginated response with envelope, metadata, and navigation links
     */
    public <T extends ScopedEntity> PagedResponse<T> findPage(
            Class<T> entityType,
            @Nullable Specification<T> filter,
            PageQuery pageQuery,
            SortAllowList allowList,
            ScopedRepository<T, ?> repo,
            String resource,
            HttpServletRequest request) {

        boolean useKeyset = pageQuery.hasCursor() || pageQuery.page() > keysetThresholdPage;
        String mode = useKeyset ? "keyset" : "offset";

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PagedResponse<T> response = useKeyset
                    ? executeKeyset(entityType, filter, pageQuery, allowList, repo, request)
                    : executeOffset(entityType, filter, pageQuery, allowList, repo, request);
            return response;
        } finally {
            sample.stop(meterRegistry.timer("page.query",
                    "resource", resource,
                    "mode", mode));
        }
    }

    // -------------------------------------------------------------------------
    // Offset mode
    // -------------------------------------------------------------------------

    private <T extends ScopedEntity> PagedResponse<T> executeOffset(
            Class<T> entityType,
            @Nullable Specification<T> filter,
            PageQuery pageQuery,
            SortAllowList allowList,
            ScopedRepository<T, ?> repo,
            HttpServletRequest request) {

        Sort sort = buildSort(pageQuery.sort(), allowList);
        Pageable pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);

        Page<T> page = scopedQueryExecutor.findAll(entityType, filter, pageable, repo);

        PageMeta meta = PageMeta.of(page.getNumber(), page.getSize(), page.getTotalElements());
        PageLinks links = buildOffsetLinks(request, page.getNumber(), page.getSize(),
                page.getTotalPages(), pageQuery);
        return PagedResponse.of(page.getContent(), meta, links);
    }

    // -------------------------------------------------------------------------
    // Keyset mode
    // -------------------------------------------------------------------------

    private <T extends ScopedEntity> PagedResponse<T> executeKeyset(
            Class<T> entityType,
            @Nullable Specification<T> filter,
            PageQuery pageQuery,
            SortAllowList allowList,
            ScopedRepository<T, ?> repo,
            HttpServletRequest request) {

        Sort sort = buildSort(pageQuery.sort(), allowList);

        // Determine the sort fingerprint for cross-order replay detection
        List<SortField> resolvedSorts = resolveSort(pageQuery.sort(), allowList);
        String fingerprint = KeysetCursor.computeFingerprint(resolvedSorts);

        // Decode cursor (if present) and bake cursor predicate into the spec
        Specification<T> composedFilter = filter;
        if (pageQuery.hasCursor()) {
            KeysetCursor cursor = KeysetCursor.decode(pageQuery.cursor(), fingerprint, cursorSecret);
            Specification<T> cursorPredicate = buildCursorPredicate(cursor);
            composedFilter = filter == null ? cursorPredicate : filter.and(cursorPredicate);
        }

        // Fetch size rows; if all come back the set may extend further
        Pageable pageable = PageRequest.of(0, pageQuery.size(), sort);
        Page<T> page = scopedQueryExecutor.findAll(entityType, composedFilter, pageable, repo);

        List<T> content = page.getContent();
        boolean hasNext = page.hasNext();

        // Encode next cursor from the last item (if it has more data)
        String nextCursor = null;
        if (hasNext && !content.isEmpty()) {
            T last = content.get(content.size() - 1);
            nextCursor = buildNextCursor(last, fingerprint);
        }

        PageMeta meta = PageMeta.keyset(pageQuery.size());
        PageLinks links = buildKeysetLinks(request, nextCursor);
        return PagedResponse.of(content, meta, links);
    }

    /**
     * Builds a keyset "after cursor" predicate for {@code (created_at DESC, id ASC)}.
     *
     * <p>Aligned with the composite index {@code idx_work_order_created_at_id}.
     * Predicate: {@code created_at < cursor_ca OR (created_at = cursor_ca AND id > cursor_id)}.
     */
    private static <T extends ScopedEntity> Specification<T> buildCursorPredicate(KeysetCursor cursor) {
        return (root, query, cb) -> {
            Instant ca = cursor.createdAt();
            UUID    id = cursor.id();
            return cb.or(
                    cb.lessThan(root.get("createdAt"), ca),
                    cb.and(
                            cb.equal(root.get("createdAt"), ca),
                            cb.greaterThan(root.get("id"), id)
                    )
            );
        };
    }

    /**
     * Encodes a next-page cursor from the last entity in the result.
     * Assumes the entity exposes {@code createdAt} and {@code id} via JPA attributes.
     */
    @SuppressWarnings("unchecked")
    private <T extends ScopedEntity> String buildNextCursor(T entity, String fingerprint) {
        try {
            java.lang.reflect.Method getCreatedAt = entity.getClass().getMethod("getCreatedAt");
            java.lang.reflect.Method getId        = entity.getClass().getMethod("getId");
            Instant createdAt = (Instant) getCreatedAt.invoke(entity);
            UUID    id        = (UUID) getId.invoke(entity);
            return new KeysetCursor(fingerprint, createdAt, id).encode(cursorSecret);
        } catch (Exception e) {
            // Entity does not expose standard accessors — keyset unavailable for this type
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Sort building
    // -------------------------------------------------------------------------

    /**
     * Resolves sort tokens through the allow-list and appends the mandatory id tie-break.
     */
    private List<SortField> resolveSort(List<SortField> clientSort, SortAllowList allowList) {
        List<SortField> resolved = new ArrayList<>();
        for (SortField sf : clientSort) {
            String persistent = allowList.resolvePersistentName(sf.field());
            resolved.add(new SortField(persistent, sf.direction()));
        }
        // Mandatory id tie-break — prevents duplicate/skipped rows under concurrent mutation
        boolean hasIdSort = resolved.stream().anyMatch(sf -> "id".equals(sf.field()));
        if (!hasIdSort) {
            resolved.add(new SortField("id", Sort.Direction.ASC));
        }
        return resolved;
    }

    private Sort buildSort(List<SortField> clientSort, SortAllowList allowList) {
        List<SortField> resolved = resolveSort(clientSort, allowList);
        List<Sort.Order> orders = resolved.stream()
                .map(sf -> new Sort.Order(sf.direction(), sf.field()))
                .toList();
        return Sort.by(orders);
    }

    // -------------------------------------------------------------------------
    // Link building
    // -------------------------------------------------------------------------

    private PageLinks buildOffsetLinks(HttpServletRequest request, int currentPage,
                                       int size, int totalPages, PageQuery pageQuery) {
        String next = (currentPage + 1 < totalPages)
                ? replacePageParam(request, currentPage + 1, size)
                : null;
        String prev = (currentPage > 0)
                ? replacePageParam(request, currentPage - 1, size)
                : null;
        return PageLinks.of(next, prev);
    }

    private PageLinks buildKeysetLinks(HttpServletRequest request, @Nullable String nextCursor) {
        String next = nextCursor != null
                ? buildCursorLink(request, nextCursor)
                : null;
        return PageLinks.of(next, null);
    }

    private static String replacePageParam(HttpServletRequest request, int page, int size) {
        return UriComponentsBuilder.fromRequest(request)
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .toUriString();
    }

    private static String buildCursorLink(HttpServletRequest request, String cursor) {
        return UriComponentsBuilder.fromRequest(request)
                .replaceQueryParam("cursor", cursor)
                .replaceQueryParam("page", (Object[]) null)
                .toUriString();
    }
}
