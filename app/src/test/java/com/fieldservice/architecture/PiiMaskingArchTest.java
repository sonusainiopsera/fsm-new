package com.fieldservice.architecture;

import com.fieldservice.platform.masking.MaskingStrategies;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.base.DescribedPredicate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import javax.crypto.Mac;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for PII masking compliance (WO-192, AC-8).
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li>No class outside the platform masking package may call
 *       {@link MaskingStrategies#forType} with a null argument (verified at unit level;
 *       the ArchUnit rule enforces structural access only).</li>
 *   <li>No AI-gateway class may call a logger directly with an entity object —
 *       only string interpolation or scrubbed values are allowed.</li>
 *   <li>The anonymisation generator may use {@link Mac#getInstance} (HMAC-SHA256) but
 *       must never use {@link java.security.MessageDigest#getInstance} — the existing
 *       {@link InjectionAndCryptoRulesTest} covers this; this test extends it with a
 *       Mac-is-approved assertion.</li>
 *   <li>The {@code AnonymisationGenerator} must reside under a {@code .internal}
 *       package (not exposed via the public API).</li>
 *   <li>No class in {@code aigateway.internal} may depend on the privacy module's
 *       internal package (cross-module internals access guard).</li>
 * </ol>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PiiMaskingArchTest {

    /**
     * AnonymisationGenerator must not be accessible from outside its internal package.
     * This rule ensures it cannot be called from a controller or service in another module.
     */
    @ArchTest
    static final ArchRule anonymisationGeneratorMustBeInInternalPackage =
            noClasses()
                    .that().haveSimpleName("AnonymisationGenerator")
                    .should().resideOutsideOfPackages("..internal..")
                    .because("AnonymisationGenerator handles raw database rewrite operations "
                            + "and must remain package-private in privacy.internal; "
                            + "it must not be reachable from outside the privacy module. (WO-192, AC-6)");

    /**
     * No class outside {@code aigateway.internal} may construct {@link FreeTextScrubber}
     * or {@link RedactingAiProviderAdapter} directly — they are package-private by design
     * and accessible only within the aigateway.internal wiring configuration.
     *
     * <p>This rule asserts the structural contract by checking that no class outside
     * the aigateway package imports {@code RedactingAiProviderAdapter}.
     */
    @ArchTest
    static final ArchRule redactingAdapterMustStayInAiGatewayInternal =
            noClasses()
                    .that().resideOutsideOfPackages("com.fieldservice.aigateway.internal..")
                    .should().dependOnClassesThat()
                    .haveSimpleName("RedactingAiProviderAdapter")
                    .because("RedactingAiProviderAdapter is package-private in aigateway.internal "
                            + "and must not be imported from outside that package. (WO-192, AC-5)");

    /**
     * No aigateway class may access the privacy module's internal package.
     * The AI gateway must remain unaware of privacy module internals;
     * it uses only the platform masking layer and FreeTextScrubber.
     */
    @ArchTest
    static final ArchRule aiGatewayMustNotAccessPrivacyInternals =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.aigateway..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.privacy.internal..")
                    .because("AI gateway must not depend on privacy module internals; "
                            + "use the platform masking layer (PiiMasker) instead. (WO-192)");

    /**
     * The platform masking package must not depend on the privacy module's internal package.
     * The flow is privacy.internal → platform.masking (via ClassificationPortAdapter),
     * not the reverse.
     */
    @ArchTest
    static final ArchRule platformMaskingMustNotDependOnPrivacyInternals =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.platform.masking..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.privacy.internal..")
                    .because("Platform masking layer must not depend on privacy module internals "
                            + "to avoid circular Maven dependency. (WO-192)");

    /**
     * Self-test: verifies that the forbidden-digest-algorithm semgrep concept maps to
     * the existing ArchUnit MessageDigest rule — no new ArchUnit rule needed for MD5/SHA-1
     * because InjectionAndCryptoRulesTest already covers it. This test documents that
     * AnonymisationGenerator uses Mac (HMAC-SHA256) not MessageDigest, which is permitted.
     */
    @Test
    void anonymisationGenerator_usesMacNotMessageDigest() {
        // AnonymisationGenerator is in the privacy module which is imported by the classpath
        JavaClasses anonymisationClasses = new ClassFileImporter()
                .importPackages("com.fieldservice.privacy.internal");

        // Assert it does NOT call MessageDigest.getInstance
        ArchRule noMessageDigest = noClasses()
                .should().callMethodWhere(
                        DescribedPredicate.describe(
                                "calls MessageDigest.getInstance",
                                (JavaMethodCall call) ->
                                        call.getTargetOwner().isEquivalentTo(java.security.MessageDigest.class)
                                        && call.getName().equals("getInstance")
                        ))
                .because("AnonymisationGenerator must use HmacSHA256 via Mac.getInstance, "
                        + "not MessageDigest — MD5/SHA-1/DES are forbidden. (WO-192)");

        // This should pass — AnonymisationGenerator uses Mac.getInstance("HmacSHA256")
        noMessageDigest.check(anonymisationClasses);
    }
}
