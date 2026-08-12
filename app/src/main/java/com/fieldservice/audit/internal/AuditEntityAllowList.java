package com.fieldservice.audit.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Allow-list of audited entity type names to their audit table metadata.
 *
 * <p>This is the single validation gateway for all user-supplied {@code entityType}
 * values. Any name not in this map is rejected with a 400 field error before any
 * query is constructed, closing the SQL injection vector via table-name interpolation.
 *
 * <p>The map is intentionally exhaustive for the business-critical entities; adding a
 * new entity to the search surface requires an explicit entry here.
 */
final class AuditEntityAllowList {

    record EntityMeta(
            String   auditTableName,
            String   entityTypeLabel,
            List<String> diffableFields,
            Set<String>  piiFields) {}

    private static final Map<String, EntityMeta> ALLOW_LIST = Map.ofEntries(
            Map.entry("WorkOrder", new EntityMeta(
                    "work_order_aud",
                    "WorkOrder",
                    List.of("state", "priority", "description", "assigned_technician_id",
                            "fault_code", "fault_category"),
                    Set.of("description"))),
            Map.entry("Assignment", new EntityMeta(
                    "assignment_aud",
                    "Assignment",
                    List.of("work_order_id", "technician_id", "assigned_at", "released_at"),
                    Set.of())),
            Map.entry("Site", new EntityMeta(
                    "site_aud",
                    "Site",
                    List.of("name", "customer_id"),
                    Set.of("name"))),
            Map.entry("AppUser", new EntityMeta(
                    "app_user_aud",
                    "AppUser",
                    List.of("email", "full_name", "active"),
                    Set.of("email", "full_name"))),
            Map.entry("TechnicianCertification", new EntityMeta(
                    "technician_certification_aud",
                    "TechnicianCertification",
                    List.of("technician_id", "certification_code", "issued_at", "expires_at"),
                    Set.of())),
            Map.entry("SlaPolicy", new EntityMeta(
                    "sla_policy_aud",
                    "SlaPolicy",
                    List.of("priority", "response_minutes", "resolution_minutes",
                            "at_risk_fraction", "effective_from", "effective_to"),
                    Set.of())),
            Map.entry("RoleAssignment", new EntityMeta(
                    "role_assignment_aud",
                    "RoleAssignment",
                    List.of("user_id", "role_name"),
                    Set.of())),
            Map.entry("Asset", new EntityMeta(
                    "asset_aud",
                    "Asset",
                    List.of("serial_number", "model", "site_id", "state"),
                    Set.of()))
    );

    /** Valid sort fields for revision search results. */
    static final Set<String> VALID_SORT_FIELDS = Set.of("revisionTimestamp", "revisionNumber");

    private AuditEntityAllowList() {}

    static Optional<EntityMeta> lookup(String entityType) {
        if (entityType == null) return Optional.empty();
        return Optional.ofNullable(ALLOW_LIST.get(entityType));
    }

    static boolean isValidEntityType(String entityType) {
        return entityType != null && ALLOW_LIST.containsKey(entityType);
    }

    static Set<String> allowedEntityTypes() {
        return ALLOW_LIST.keySet();
    }

    static String auditTableFor(String entityType) {
        EntityMeta meta = ALLOW_LIST.get(entityType);
        if (meta == null) {
            throw new IllegalArgumentException("Entity type not in allow-list: " + entityType);
        }
        return meta.auditTableName();
    }
}
