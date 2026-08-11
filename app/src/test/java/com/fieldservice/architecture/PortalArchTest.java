package com.fieldservice.architecture;

import com.fieldservice.platform.persistence.ScopedRepository;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for the portal module (WO-169).
 *
 * <ul>
 *   <li>Portal repositories that are domain query targets must extend
 *       {@link ScopedRepository}; infrastructure repositories (linkage resolution)
 *       are allow-listed.</li>
 *   <li>No portal controller may depend on a repository type directly; all data
 *       access must route through a service.</li>
 *   <li>Portal code must not reach into other module internals (only public APIs).</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.fieldservice.portal")
class PortalArchTest {

    /**
     * Infrastructure repositories that legitimately bypass ScopedRepository.
     * Any future portal domain query repository (e.g., a portal work-order view)
     * must NOT be added here — it must extend ScopedRepository instead.
     */
    private static final String PORTAL_NON_SCOPED_REPOS =
            "PortalAccountUserRepository|PortalInvitationRepository";

    @ArchTest
    static final ArchRule portalDomainRepositoriesMustBeScoped =
            classes()
                    .that().areInterfaces()
                    .and().haveSimpleNameEndingWith("Repository")
                    .and().areAssignableTo(JpaRepository.class)
                    .and().haveSimpleNameNotMatching(PORTAL_NON_SCOPED_REPOS)
                    .should().beAssignableTo(ScopedRepository.class)
                    .because("portal query repositories must extend ScopedRepository so row-scope "
                            + "predicates are mandatory; add infrastructure-only repos to the "
                            + "allow-list in PortalArchTest");

    @ArchTest
    static final ArchRule portalControllersMustNotAccessRepositoriesDirectly =
            noClasses()
                    .that().areAnnotatedWith(Controller.class)
                    .or().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("portal controllers must delegate all data access to service classes; "
                            + "direct repository access bypasses method-security and scope enforcement");

    @ArchTest
    static final ArchRule portalMustNotReachIntoModuleInternals =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.portal..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.fieldservice.analytics.internal..",
                            "com.fieldservice.workorder.lifecycle..",
                            "com.fieldservice.sla.internal..",
                            "com.fieldservice.inventory.internal.."
                    )
                    .because("portal code must depend only on published module APIs, not "
                            + "on internal implementation packages of other modules");
}
