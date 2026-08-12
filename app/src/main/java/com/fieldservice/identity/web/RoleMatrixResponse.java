package com.fieldservice.identity.web;

import com.fieldservice.identity.domain.AppRole;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read projection of the active role-to-permission matrix. */
public record RoleMatrixResponse(List<RoleEntry> data) {

    public record RoleEntry(
            String       role,
            List<String> permissions
    ) {}

    public static RoleMatrixResponse from(
            Map<AppRole, List<String>> matrix) {
        List<RoleEntry> entries = matrix.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        (a, b) -> a.name().compareTo(b.name())))
                .map(e -> new RoleEntry(e.getKey().name(), e.getValue()))
                .collect(Collectors.toList());
        return new RoleMatrixResponse(entries);
    }
}
