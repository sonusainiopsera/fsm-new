package com.fieldservice.platform.pagination;

import java.util.Map;

/**
 * Maps public client-facing sort field names to JPA persistent property names for a specific resource.
 *
 * <p>Client-supplied sort identifiers must never be interpolated directly into queries.
 * Every sort field passes through this allow-list: unknown names are rejected with
 * {@link InvalidSortException} before any query is constructed, preventing JPQL injection.
 *
 * <p>Usage example (work order resource):
 * <pre>{@code
 * SortAllowList WORK_ORDER_SORTS = SortAllowList.of(
 *     "createdAt",   "createdAt",
 *     "title",       "title",
 *     "state",       "state",
 *     "priority",    "priority",
 *     "slaDeadline", "slaDeadline"
 * );
 * }</pre>
 *
 * @param allowedFields map of {@code publicName → persistentPropertyName}
 */
public record SortAllowList(Map<String, String> allowedFields) {

    /**
     * Creates a {@link SortAllowList} from interleaved pairs of {@code publicName, persistentName}.
     *
     * @param pairs alternating public field names and persistent property names
     * @throws IllegalArgumentException if {@code pairs} has an odd length
     */
    public static SortAllowList of(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("pairs must be an even number of strings");
        }
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return new SortAllowList(Map.copyOf(map));
    }

    /**
     * Maps a public client sort field name to the persistent property name.
     *
     * @param publicName the field name as supplied by the client
     * @return the JPA persistent property name
     * @throws InvalidSortException if {@code publicName} is not in the allow-list
     */
    public String resolvePersistentName(String publicName) {
        String persistent = allowedFields.get(publicName);
        if (persistent == null) {
            throw new InvalidSortException(publicName);
        }
        return persistent;
    }
}
