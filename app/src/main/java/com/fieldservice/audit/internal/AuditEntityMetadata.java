package com.fieldservice.audit.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Allow-listed map of audited entity type names to their audit table metadata.
 *
 * <p>Any entity type name not present in {@link #ALL} is rejected with 400 before any
 * query is executed. This is the SQL-injection protection layer for entity type inputs.
 *
 * <p>PII_FIELDS lists the fields whose values must be masked in search results and exports.
 */
final class AuditEntityMetadata {

    /** Stable entity type name → audit table name. */
    static final Map<String, String> AUDIT_TABLE = new LinkedHashMap<>();

    /** Fields that must be masked in search results and exports (PII classification). */
    static final Map<String, Set<String>> PII_FIELDS = new LinkedHashMap<>();

    static {
        AUDIT_TABLE.put("WorkOrder",    "work_order_aud");
        AUDIT_TABLE.put("AppUser",      "app_user_aud");
        AUDIT_TABLE.put("Site",         "site_aud");
        AUDIT_TABLE.put("Assignment",   "assignment_aud");
        AUDIT_TABLE.put("SlaPolicy",    "sla_policy_aud");
        AUDIT_TABLE.put("Customer",     "customer_aud");
        AUDIT_TABLE.put("Asset",        "asset_aud");
        AUDIT_TABLE.put("Technician",   "technician_aud");

        PII_FIELDS.put("AppUser",  Set.of("emailAddress", "phoneNumber", "displayName"));
        PII_FIELDS.put("Customer", Set.of("contactName", "contactEmail", "contactPhone",
                                           "billingAddress", "billingPostcode"));
        PII_FIELDS.put("Site",     Set.of("address", "postcode", "contactName"));
        PII_FIELDS.put("Technician", Set.of("displayName", "emailAddress", "phoneNumber",
                                             "homeAddress"));
    }

    /** Returns true if {@code entityType} is in the allow-list. */
    static boolean isAllowed(String entityType) {
        return entityType == null || AUDIT_TABLE.containsKey(entityType);
    }

    /** Returns the allow-listed set of entity type names for validation messages. */
    static Set<String> allowedTypes() {
        return AUDIT_TABLE.keySet();
    }

    private AuditEntityMetadata() {}
}
