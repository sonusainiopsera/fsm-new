package com.fieldservice.platform.pagination;

import org.springframework.data.domain.Sort;

/**
 * A single sort directive as received from the client.
 *
 * <p>The {@code field} is the <em>public API name</em> as supplied by the client; it is mapped to
 * a persistent property name by {@link SortAllowList} before any query is constructed, so
 * client-supplied identifiers never reach a query as raw strings (SQL/JPQL injection defence).
 *
 * @param field     public field name from the client request
 * @param direction ascending or descending
 */
public record SortField(String field, Sort.Direction direction) {

    /**
     * Parses a sort token of the form {@code field} (defaults to ASC) or {@code field:DESC}.
     *
     * @param token the raw sort token
     * @return a parsed {@link SortField}
     * @throws InvalidSortException if the token format is unrecognisable
     */
    public static SortField parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidSortException("sort", "Sort token must not be blank.");
        }
        String[] parts = token.split(":", 2);
        String field = parts[0].strip();
        if (field.isEmpty()) {
            throw new InvalidSortException("sort", "Sort field name must not be empty.");
        }
        Sort.Direction dir = Sort.Direction.DESC; // default
        if (parts.length == 2) {
            String rawDir = parts[1].strip().toUpperCase();
            if ("ASC".equals(rawDir)) {
                dir = Sort.Direction.ASC;
            } else if ("DESC".equals(rawDir)) {
                dir = Sort.Direction.DESC;
            } else {
                throw new InvalidSortException("sort",
                        "Sort direction '" + rawDir + "' is not valid; use ASC or DESC.");
            }
        }
        return new SortField(field, dir);
    }
}
