package com.fieldservice.app.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness test (EPIC-01 method-security gate): every {@code @Service} class
 * in a domain package must have {@code @PreAuthorize} (or an equivalent annotation) on
 * every declared public, non-static method.
 *
 * <p>The rule covers {@code com.fieldservice.workorder} — the domain package where
 * unannotated service methods would be most dangerous. Identity infrastructure services
 * ({@code LoginService}, {@code RefreshTokenService}) serve unauthenticated requests and
 * are intentionally excluded from this scope.
 *
 * <h3>Test layout</h3>
 * <ol>
 *   <li>{@link #all_domain_service_methods_have_preauthorize} — positive rule confirming
 *       the production workorder services are compliant.</li>
 *   <li>{@link #rule_fires_on_non_compliant_fixture} — proves the rule still enforces by
 *       applying it to {@code UnprotectedServiceFixture} and asserting an
 *       {@link AssertionError} is raised.</li>
 * </ol>
 */
class MethodSecurityTest {

    private static JavaClasses DOMAIN_CLASSES;

    @BeforeAll
    static void importClasses() {
        DOMAIN_CLASSES = new ClassFileImporter().importPackages(
                "com.fieldservice.workorder"
        );
    }

    /**
     * Verifies that every public, non-static method declared on a {@code @Service} class
     * in the workorder domain package carries a {@code @PreAuthorize} annotation.
     *
     * <p>Constructors are excluded — authorization annotations on constructors are not
     * meaningful in Spring Security.
     */
    @Test
    @DisplayName("All @Service methods in workorder domain have @PreAuthorize")
    void all_domain_service_methods_have_preauthorize() {
        classes()
                .that().areAnnotatedWith(Service.class)
                .should(havePreAuthorizeOnAllPublicMethods())
                .check(DOMAIN_CLASSES);
    }

    /**
     * Proves the rule still fires: applies the check to the deliberately non-compliant
     * fixture class and asserts that an {@link AssertionError} is raised.
     */
    @Test
    @DisplayName("Rule fires on deliberately non-compliant fixture (proves rule still enforces)")
    void rule_fires_on_non_compliant_fixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importPackages("com.fieldservice.app.arch.fixture");

        assertThatThrownBy(() ->
                classes()
                        .that().areAnnotatedWith(Service.class)
                        .should(havePreAuthorizeOnAllPublicMethods())
                        .check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnprotectedServiceFixture");
    }

    // -------------------------------------------------------------------------
    // Custom ArchCondition
    // -------------------------------------------------------------------------

    private static ArchCondition<JavaClass> havePreAuthorizeOnAllPublicMethods() {
        return new ArchCondition<>("have @PreAuthorize on all public declared methods") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (JavaMethod method : clazz.getMethods()) {
                    if (!method.getModifiers().contains(
                            com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) {
                        continue;
                    }
                    if (method.getModifiers().contains(
                            com.tngtech.archunit.core.domain.JavaModifier.STATIC)) {
                        continue;
                    }
                    boolean annotated = method.isAnnotatedWith(PreAuthorize.class)
                            || method.isAnnotatedWith(
                                    org.springframework.security.access.annotation.Secured.class);
                    if (!annotated) {
                        events.add(SimpleConditionEvent.violated(clazz,
                                clazz.getSimpleName() + "." + method.getName()
                                + "() is public and @Service-scoped but lacks @PreAuthorize. "
                                + "Add @PreAuthorize or move the method to a non-service class."));
                    }
                }
            }
        };
    }
}
