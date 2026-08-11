package com.fieldservice.app.security;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that every public method on {@code @Service} classes in the domain modules
 * carries a Spring Security authorization annotation ({@link PreAuthorize} or {@link PostAuthorize}).
 *
 * <p>Per AC-1: an unannotated public service method is rejected by the security
 * configuration convention.
 */
class MethodSecurityAnnotationTest {

    @Test
    void all_public_service_methods_have_authorization_annotation() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("com.fieldservice.domain");

        List<String> violations = new ArrayList<>();

        for (var javaClass : classes) {
            if (!javaClass.isAnnotatedWith(Service.class)) continue;

            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) continue;
                // Skip toString, hashCode, equals from Object
                if (isObjectMethod(method.getName())) continue;

                boolean hasPreAuthorize = method.isAnnotatedWith(PreAuthorize.class);
                boolean hasPostAuthorize = method.isAnnotatedWith(PostAuthorize.class);

                if (!hasPreAuthorize && !hasPostAuthorize) {
                    violations.add(javaClass.getName() + "#" + method.getName());
                }
            }
        }

        assertThat(violations)
                .as("Public service methods missing @PreAuthorize / @PostAuthorize:\n%s\n"
                        + "Every public service method must carry an authorization annotation. "
                        + "Add @PreAuthorize(\"hasAnyRole(...)\")",
                        String.join("\n", violations))
                .isEmpty();
    }

    private boolean isObjectMethod(String name) {
        return name.equals("toString") || name.equals("hashCode")
                || name.equals("equals") || name.equals("getClass")
                || name.equals("notify") || name.equals("notifyAll") || name.equals("wait");
    }
}
