package com.fieldservice.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for the analytics module boundary (WO-161, AC-1).
 *
 * <p>Rules enforced at compile time:
 * <ul>
 *   <li>No class outside the analytics module may reach into {@code analytics.internal}
 *       (package-private implementation boundary).</li>
 *   <li>Analytics module classes may not depend on another module's JPA entity or repository
 *       packages — the read model consumes domain events and re-projects from the replica;
 *       it must not reach into domain tables directly.</li>
 *   <li>The analytics module must not depend on the AI gateway (P0 boundary isolation).</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AnalyticsBoundaryTest {

    /**
     * AC-1: no class outside the analytics module may import {@code analytics.internal}.
     * The public surface is {@code analytics.KpiProjectionQuery} and {@code analytics.KpiProjection}
     * only.
     */
    @ArchTest
    static final ArchRule outsideModulesMustNotAccessAnalyticsInternals =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.fieldservice.analytics..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.analytics.internal")
                    .because("analytics.internal is package-private implementation; callers must " +
                             "depend on analytics.KpiProjectionQuery only (WO-161, AC-1)");

    /**
     * AC-1: analytics module must not directly depend on other modules' entity or
     * repository packages (domain.workorder, domain.inventory, domain.sla, etc.).
     *
     * <p>The read model receives domain events through the outbox and re-aggregates
     * from the replica — it must not couple to entity classes.
     */
    @ArchTest
    static final ArchRule analyticsModuleMustNotDependOnDomainEntities =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.analytics..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.fieldservice.domain.workorder",
                            "com.fieldservice.domain.inventory",
                            "com.fieldservice.domain.sla",
                            "com.fieldservice.domain.assignment",
                            "com.fieldservice.domain.catalog",
                            "com.fieldservice.domain.site",
                            "com.fieldservice.domain.asset"
                    )
                    .because("analytics.internal may not reach into another module's domain entity " +
                             "or repository packages — it reads from the replica via JDBC only " +
                             "(WO-161, AC-1, AC-8)");

    /**
     * P0 boundary: analytics must not depend on the AI gateway.
     */
    @ArchTest
    static final ArchRule analyticsModuleMustNotDependOnAiGateway =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.analytics..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.fieldservice.aigateway..")
                    .because("analytics is a P0-adjacent read path; an AI provider outage " +
                             "must never cascade into KPI projection (WO-161, P0 isolation)");
}
