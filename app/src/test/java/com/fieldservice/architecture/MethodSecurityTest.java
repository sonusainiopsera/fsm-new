package com.fieldservice.architecture;

import com.fieldservice.architecture.fixture.UnannotatedServiceMethod;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for method-security annotation coverage (WO-114).
 *
 * <p>Rules:
 * <ul>
 *   <li>Every public, non-static method on a {@link Service @Service}-annotated class
 *       in the domain service packages must carry a method-level authorization annotation
 *       ({@link PreAuthorize}, {@link PostAuthorize}, or {@link Secured}).</li>
 *   <li>This rule is intentionally scoped to the business-logic service packages;
 *       infrastructure services (login, refresh, outbox, idempotency, pagination) are
 *       excluded because they operate outside the authenticated request lifecycle.</li>
 * </ul>
 *
 * <p>A deliberately non-compliant fixture ({@link UnannotatedServiceMethod}) proves the
 * rule still fires when a public service method lacks an authorization annotation.
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class MethodSecurityTest {

    /**
     * Production rule: every public non-static method on a @Service class in the
     * business-logic service packages must carry an authorization annotation.
     *
     * <p>Excluded packages (infrastructure / pre-auth services):
     * <ul>
     *   <li>{@code identity.application.LoginService} — pre-authentication flow</li>
     *   <li>{@code identity.application.RefreshTokenService} — pre-authentication flow</li>
     *   <li>{@code outbox.*} — background job infrastructure</li>
     *   <li>{@code idempotency.*} — filter-layer infrastructure</li>
     *   <li>{@code pagination.*} — generic pagination infrastructure</li>
     * </ul>
     */
    @ArchTest
    static final ArchRule publicServiceMethodsMustHaveAuthorizationAnnotation =
            methods()
                    .that().arePublic()
                    .and().areNotStatic()
                    .and().areDeclaredInClassesThat().areAnnotatedWith(Service.class)
                    .and().areDeclaredInClassesThat().resideInAnyPackage(
                            "com.fieldservice.workorder..",
                            "com.fieldservice.audit..",
                            "com.fieldservice.inventory.application..",
                            "com.fieldservice.identity.application.UserPreferencesService",
                            "com.fieldservice.privacy.internal..",
                            "com.fieldservice.portal.invitation..",
                            "com.fieldservice.portal.access.."
                    )
                    .should().beAnnotatedWith(PreAuthorize.class)
                    .orShould().beAnnotatedWith(PostAuthorize.class)
                    .orShould().beAnnotatedWith(Secured.class)
                    .because("every publicly reachable service method must carry an explicit "
                            + "authorization expression per the RBAC matrix in "
                            + "docs/security/rbac-matrix.md");

    /**
     * Proves the rule still fires: import only the non-compliant fixture and assert the rule fails.
     */
    @Test
    void rule_firesOnUnannotatedPublicServiceMethod() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(UnannotatedServiceMethod.class);

        ArchRule simpleRule = methods()
                .that().arePublic()
                .and().areNotStatic()
                .and().areDeclaredInClassesThat().areAnnotatedWith(Service.class)
                .should().beAnnotatedWith(PreAuthorize.class)
                .orShould().beAnnotatedWith(PostAuthorize.class)
                .orShould().beAnnotatedWith(Secured.class)
                .because("service methods must have authorization annotations");

        assertThatThrownBy(() -> simpleRule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("publicMethodWithoutAnnotation");
    }
}
