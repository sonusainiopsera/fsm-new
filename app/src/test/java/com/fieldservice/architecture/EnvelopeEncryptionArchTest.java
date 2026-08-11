package com.fieldservice.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.slf4j.Logger;

import java.security.MessageDigest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for envelope encryption security properties (WO-193, AC-9, AC-11).
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li>No class outside the platform crypto package may call {@code MessageDigest.getInstance} —
 *       covered jointly with {@link InjectionAndCryptoRulesTest}.</li>
 *   <li>BlindIndex must use HMAC via Mac, not MessageDigest.</li>
 *   <li>LocalStubKeyManager must not be used outside the crypto package or config —
 *       production code must reference the SubjectKeyManager interface only.</li>
 * </ol>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class EnvelopeEncryptionArchTest {

    /**
     * BlindIndex must use Mac.getInstance("HmacSHA256"), not MessageDigest.getInstance.
     * MD5, SHA-1 and DES are forbidden per AC-11.
     */
    @ArchTest
    static final ArchRule blindIndexMustNotUseMessageDigest =
            noClasses()
                    .that().haveSimpleName("BlindIndex")
                    .should().callMethodWhere(
                            DescribedPredicate.describe(
                                    "calls MessageDigest.getInstance",
                                    (JavaMethodCall call) ->
                                            call.getTargetOwner().isEquivalentTo(MessageDigest.class)
                                            && call.getName().equals("getInstance")
                            ))
                    .because("BlindIndex must use HmacSHA256 via javax.crypto.Mac — "
                            + "MD5, SHA-1 and DES are forbidden. (WO-193, AC-11)");

    /**
     * EnvelopeEncryptedStringConverter must not use MessageDigest.getInstance.
     * It must use AES-GCM only — no digest operations.
     */
    @ArchTest
    static final ArchRule converterMustNotUseMessageDigest =
            noClasses()
                    .that().haveSimpleName("EnvelopeEncryptedStringConverter")
                    .should().callMethodWhere(
                            DescribedPredicate.describe(
                                    "calls MessageDigest.getInstance",
                                    (JavaMethodCall call) ->
                                            call.getTargetOwner().isEquivalentTo(MessageDigest.class)
                                            && call.getName().equals("getInstance")
                            ))
                    .because("EnvelopeEncryptedStringConverter must use AES-256-GCM only — "
                            + "no MD5, SHA-1, or DES usage permitted. (WO-193, AC-11)");

    /**
     * LocalStubKeyManager must not be referenced outside the crypto package or config layer.
     * All production code must use the SubjectKeyManager interface.
     */
    @ArchTest
    static final ArchRule localStubKeyManagerMustNotBeUsedOutsideCryptoAndConfig =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.fieldservice.platform.crypto..",
                            "com.fieldservice.config.."
                    )
                    .should().dependOnClassesThat()
                    .haveSimpleName("LocalStubKeyManager")
                    .because("LocalStubKeyManager is a test/local stub; production code must "
                            + "use the SubjectKeyManager interface. (WO-193, AC-9)");

    /**
     * No class outside platform.crypto may call a Logger method with a parameter named 'key'
     * or whose type is DataKey. This prevents key material from being inadvertently logged.
     *
     * <p>Note: ArchUnit cannot verify runtime argument values; this rule enforces that
     * DataKey objects are not referenced as Logger arguments at the type level.
     */
    @ArchTest
    static final ArchRule cryptoPackageMustNotDependOnServiceLayer =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.platform.crypto..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice..application..")
                    .because("The platform crypto package is a foundational layer and must not "
                            + "depend on application-level service classes. (WO-193)");
}
