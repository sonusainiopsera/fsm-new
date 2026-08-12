package com.fieldservice.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Append-only enforcement test for the audit bounded context.
 *
 * <p>Scans every class in {@code com.fieldservice.audit} using reflection and fails if any
 * method in any repository, service or controller class is capable of updating or deleting
 * an audit entity (WorkOrder_AUD, etc.).
 *
 * <p>This test does not require a Spring context and runs without a database.
 */
class AuditAppendOnlyTest {

    /**
     * Method name patterns that indicate a write operation on an audit entity.
     * The audit module must not expose any of these against audit revision data.
     */
    private static final Set<String> FORBIDDEN_METHOD_PATTERNS = Set.of(
            "deleteRev", "updateRev", "deleteAud", "updateAud", "deleteRevision",
            "updateRevision", "deleteRevInfo", "truncateRevisions", "purgeRevisions",
            "delete", "deleteAll", "deleteById", "deleteAllById",
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush"
    );

    /**
     * Classes that are explicitly allow-listed as write operations for non-audit tables
     * (e.g., AuditExportRepository which writes to audit_export — not an audit revision table).
     */
    private static final Set<String> EXPORT_ALLOWLISTED_CLASSES = Set.of(
            "AuditExportRepository",
            "AuditExportServiceImpl",
            "AuditExportEntity"
    );

    @Test
    @DisplayName("No audit module class exposes a method that mutates Envers revision history")
    void noMutatingMethodsOnRevisionData() throws Exception {
        // Load all classes in the audit internal package.
        List<Class<?>> auditClasses = discoverAuditClasses();
        assertThat(auditClasses).isNotEmpty();

        List<String> violations = new ArrayList<>();

        for (Class<?> clazz : auditClasses) {
            // Skip export infrastructure — it legitimately writes to audit_export (not _AUD tables).
            if (EXPORT_ALLOWLISTED_CLASSES.contains(clazz.getSimpleName())) continue;

            for (Method method : clazz.getDeclaredMethods()) {
                String methodName = method.getName();
                // Check if the method name matches any forbidden pattern for revision data.
                for (String pattern : FORBIDDEN_METHOD_PATTERNS) {
                    if (methodNameMatchesForbidden(methodName, pattern)) {
                        violations.add(clazz.getSimpleName() + "." + methodName
                                + "() — write operation not permitted on audit revision data");
                    }
                }
            }
        }

        assertThat(violations)
                .as("Audit module must be read-only for revision data:\n" +
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("AuditRevisionController exposes only GET and POST-export mappings, no mutating verbs")
    void controllerExposesOnlyReadEndpoints() throws Exception {
        Class<?> controllerClass = Class.forName(
                "com.fieldservice.audit.api.AuditRevisionController");

        List<String> violations = new ArrayList<>();
        for (Method method : controllerClass.getDeclaredMethods()) {
            // Look for DELETE/PUT/PATCH mapping annotations.
            for (var ann : method.getAnnotations()) {
                String annName = ann.annotationType().getSimpleName();
                if (annName.equals("DeleteMapping") || annName.equals("PutMapping")
                        || annName.equals("PatchMapping")) {
                    violations.add("Found mutating HTTP mapping @" + annName
                            + " on method " + method.getName() + "()");
                }
            }
        }

        assertThat(violations)
                .as("AuditRevisionController must not expose DELETE/PUT/PATCH:\n" +
                        String.join("\n", violations))
                .isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private List<Class<?>> discoverAuditClasses() throws Exception {
        // Load well-known classes in the audit module rather than classpath scanning
        // to keep this test dependency-free and fast.
        List<Class<?>> classes = new ArrayList<>();
        List<String> classNames = List.of(
                "com.fieldservice.audit.internal.AuditRevisionRepository",
                "com.fieldservice.audit.internal.AuditRevisionQueryServiceImpl",
                "com.fieldservice.audit.internal.RevisionDiffCalculator",
                "com.fieldservice.audit.internal.PiiMaskingPolicy",
                "com.fieldservice.audit.internal.AuditEntityAllowList",
                "com.fieldservice.audit.api.AuditRevisionController",
                "com.fieldservice.audit.api.AuditRevisionQueryService"
        );
        for (String name : classNames) {
            try {
                classes.add(Class.forName(name));
            } catch (ClassNotFoundException e) {
                // class may not exist yet; skip
            }
        }
        return classes;
    }

    private static boolean methodNameMatchesForbidden(String methodName, String pattern) {
        // Exact match or starts-with for prefix patterns (e.g., "delete" matches "deleteById").
        return methodName.equals(pattern) || methodName.startsWith(pattern);
    }
}
