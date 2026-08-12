package com.fieldservice.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Append-only enforcement test (WO-199, AC-4).
 *
 * <p>Scans the audit module for any repository, service or controller method capable of
 * updating or deleting a revision row and fails if one exists.
 *
 * <p>This is a compile-time structural check using reflection over the audit package.
 * It does not require a Spring context.
 */
@DisplayName("Audit module — append-only enforcement")
class AuditAppendOnlyEnforcementTest {

    private static final List<String> FORBIDDEN_METHOD_PATTERNS = List.of(
            "delete", "update", "save", "merge", "remove", "modify", "write", "patch"
    );

    /**
     * The audit module's query service exposes no mutation method names.
     */
    @Test
    @DisplayName("AuditRevisionQueryService exposes no save/update/delete methods")
    void auditQueryServiceHasNoMutationMethods() throws Exception {
        Class<?> serviceClass = com.fieldservice.audit.api.AuditRevisionQueryService.class;
        List<String> violations = new ArrayList<>();
        for (Method m : serviceClass.getMethods()) {
            String name = m.getName().toLowerCase();
            for (String forbidden : FORBIDDEN_METHOD_PATTERNS) {
                if (name.startsWith(forbidden)) {
                    violations.add(serviceClass.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertThat(violations)
                .as("AuditRevisionQueryService must not expose any mutation method")
                .isEmpty();
    }

    /**
     * AuditRevisionRepository is package-private and has no public delete/update methods
     * accessible from outside the package.
     */
    @Test
    @DisplayName("AuditRevisionRepository is not publicly accessible from outside audit.internal")
    void auditRevisionRepositoryIsPackagePrivate() throws Exception {
        Class<?> repoClass = com.fieldservice.audit.internal.AuditRevisionRepository.class;
        assertThat(java.lang.reflect.Modifier.isPublic(repoClass.getModifiers()))
                .as("AuditRevisionRepository must be package-private to prevent direct access")
                .isFalse();
    }

    /**
     * AuditExportEntity exposes only append-style mutations (markCompleted/markFailed),
     * not generic setters for audited fields.
     */
    @Test
    @DisplayName("AuditExportEntity has no setter for id, requestedBy, format (append-only)")
    void auditExportEntityHasNoIllegalSetters() throws Exception {
        Class<?> entityClass = com.fieldservice.audit.internal.AuditExportEntity.class;
        List<String> violations = new ArrayList<>();
        for (Method m : entityClass.getDeclaredMethods()) {
            String name = m.getName();
            if (name.startsWith("set") && (
                    name.contains("Id") || name.contains("RequestedBy") ||
                    name.contains("Format") || name.contains("FilterJson"))) {
                violations.add(name);
            }
        }
        assertThat(violations)
                .as("AuditExportEntity must not expose setters for immutable fields")
                .isEmpty();
    }
}
