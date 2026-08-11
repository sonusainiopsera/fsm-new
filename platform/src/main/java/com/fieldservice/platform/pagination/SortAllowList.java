package com.fieldservice.platform.pagination;

import java.util.Map;

/**
 * Maps public-facing sort field names to JPA persistent property names.
 *
 * <p>Client-supplied sort identifiers are validated against this allow-list before
 * being used in queries. Unknown names throw {@link InvalidSortException}, which the
 * global handler maps to 400. This prevents sort-injection attacks where a client
 * attempts to supply raw SQL identifiers or column expressions.
 *
 * <p>Example:
 * <pre>
 * SortAllowList.of(Map.of(
 *     "createdAt", "createdAt",
 *     "title",     "title",
 *     "state",     "state",
 *     "priority",  "priority"
 * ))
 * </pre>
 */
public record SortAllowList(Map<String, String> fields) {

    public SortAllowList {
        fields = Map.copyOf(fields);
    }

    public static SortAllowList of(Map<String, String> fields) {
        return new SortAllowList(fields);
    }

    /**
     * Returns the persistent property name for {@code publicName}, or throws
     * {@link InvalidSortException} if the name is not in the allow-list.
     */
    public String resolve(String publicName) {
        String persistent = fields.get(publicName);
        if (persistent == null) {
            throw new InvalidSortException(publicName);
        }
        return persistent;
    }

    public boolean allows(String publicName) {
        return fields.containsKey(publicName);
    }
}
