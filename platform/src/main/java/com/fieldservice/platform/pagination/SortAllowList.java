package com.fieldservice.platform.pagination;

import com.fieldservice.platform.api.exception.InvalidSortException;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-resource mapping from client-supplied sort field names to JPA property names.
 *
 * <p>Client-supplied sort values are <em>never</em> interpolated into queries as raw
 * identifiers — every sort field goes through this allow-list before reaching Spring Data.
 * An unknown field name produces an {@link InvalidSortException} which the
 * {@link com.fieldservice.platform.web.GlobalExceptionHandler} maps to 400.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SortAllowList allow = SortAllowList.of(Map.of(
 *     "createdAt", "createdAt",
 *     "priority",  "priority"
 * ), "createdAt");
 *
 * Sort sort = allow.parse("priority:asc");
 * }</pre>
 */
public final class SortAllowList {

    /** Public field name → JPA property name. */
    private final Map<String, String> allowed;

    /** JPA property name of the default sort field (applied when no sort param given). */
    private final String defaultProperty;

    private SortAllowList(Map<String, String> allowed, String defaultProperty) {
        this.allowed = Map.copyOf(allowed);
        this.defaultProperty = defaultProperty;
    }

    public static SortAllowList of(Map<String, String> allowed, String defaultProperty) {
        return new SortAllowList(allowed, defaultProperty);
    }

    /**
     * Returns the set of allowed public field names for documentation.
     */
    public java.util.Set<String> allowedFields() {
        return allowed.keySet();
    }

    /**
     * Parses a sort parameter string and returns the corresponding {@link Sort}.
     *
     * <p>Format: {@code "field:direction"} or just {@code "field"} (defaults to ascending).
     * Multiple fields can be specified as a comma-separated list:
     * {@code "priority:asc,createdAt:desc"}.
     *
     * <p>The returned sort always has an {@code id} tie-break appended at the end
     * (ascending) to guarantee deterministic ordering regardless of the primary sort.
     *
     * @param sortParam the raw sort string from the request, may be null
     * @return a {@link Sort} with tie-break
     * @throws InvalidSortException if any field name is not in the allow-list,
     *                              or if the direction is not {@code asc} or {@code desc}
     */
    public Sort parse(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.by(Sort.Direction.DESC, defaultProperty)
                       .and(Sort.by(Sort.Direction.ASC, "id"));
        }

        List<Sort.Order> orders = java.util.Arrays.stream(sortParam.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(this::parseToken)
                .toList();

        Sort sort = Sort.by(orders);
        // Append id tie-break if not already present
        boolean hasIdTieBreak = orders.stream().anyMatch(o -> "id".equals(o.getProperty()));
        if (!hasIdTieBreak) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return sort;
    }

    /**
     * Computes a canonical fingerprint string for a {@link Sort}, used as the
     * sort-order proof embedded in keyset cursors.
     *
     * <p>Example: {@code "createdAt:desc,id:asc"}
     */
    public static String fingerprint(Sort sort) {
        return sort.stream()
                .map(o -> o.getProperty() + ":" + o.getDirection().name().toLowerCase())
                .collect(Collectors.joining(","));
    }

    private Sort.Order parseToken(String token) {
        String[] parts = token.split(":", 2);
        String publicName = parts[0].strip();
        String jpaProperty = allowed.get(publicName);
        if (jpaProperty == null) {
            throw new InvalidSortException("sort", publicName);
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String dir = parts[1].strip().toLowerCase();
            if ("desc".equals(dir)) {
                direction = Sort.Direction.DESC;
            } else if (!"asc".equals(dir)) {
                throw new InvalidSortException("sort", token);
            }
        }
        return new Sort.Order(direction, jpaProperty);
    }
}
