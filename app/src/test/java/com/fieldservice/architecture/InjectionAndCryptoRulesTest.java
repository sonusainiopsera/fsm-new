package com.fieldservice.architecture;

import com.fieldservice.architecture.fixture.DeprecatedCryptoUsage;
import com.fieldservice.architecture.fixture.NativeQueryViolation;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for injection risk and cryptography safety (WO-200, AC-5, AC-6).
 *
 * <h3>Injection-risk rule (AC-5)</h3>
 * <p>No application class may call {@link EntityManager#createNativeQuery(String)} directly.
 * Native queries bypass the ORM query model and introduce SQL injection risk when the argument
 * is constructed at runtime. All queries must use Spring Data's {@code @Query} annotation with
 * named parameters, which passes the statement through the ORM's parameterisation pipeline.
 *
 * <h3>Cryptography safety rule (AC-6)</h3>
 * <p>No application class may call {@link MessageDigest#getInstance(String)} outside the
 * explicitly allow-listed packages. {@code MessageDigest.getInstance("MD5")},
 * {@code getInstance("SHA-1")}, and {@code getInstance("DES")} are broken for security use.
 * The approved minimum is SHA-256.
 *
 * <p>Allowed packages for {@code MessageDigest} usage (SHA-256 only, via explicit annotation
 * or code review):
 * <ul>
 *   <li>{@code com.fieldservice.identity.application.*} — token hash fingerprinting</li>
 *   <li>{@code com.fieldservice.idempotency.*} — request deduplication hash</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class InjectionAndCryptoRulesTest {

    /**
     * Approved packages where SHA-256 {@code MessageDigest} usage has been reviewed and
     * justified (2026-08-11). Any new addition must be approved via the security review process
     * documented in {@code TESTING.md §ArchUnit Rules}.
     */
    private static final String[] APPROVED_DIGEST_PACKAGES = {
            "com.fieldservice.identity.application",  // token hash fingerprinting (SHA-256 only)
            "com.fieldservice.idempotency"             // idempotency key hashing (SHA-256 only)
    };

    /**
     * No class outside the approved digest packages may call {@link MessageDigest#getInstance}.
     *
     * <p>This prevents the accidental introduction of weak algorithms (MD5, SHA-1, DES) in new
     * code. The approved packages have been manually reviewed to confirm SHA-256-only usage.
     */
    @ArchTest
    static final ArchRule messageDigestMustNotBeCalledOutsideApprovedPackages =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.fieldservice.identity.application",
                            "com.fieldservice.idempotency"
                    )
                    .should().callMethodWhere(
                            DescribedPredicate.describe(
                                    "calls MessageDigest.getInstance",
                                    (JavaMethodCall call) ->
                                            call.getTargetOwner().isEquivalentTo(MessageDigest.class)
                                            && call.getName().equals("getInstance")
                            ))
                    .because("MessageDigest.getInstance must only be called from packages that have "
                            + "been approved to use SHA-256; MD5, SHA-1, and DES are forbidden. "
                            + "See TESTING.md §ArchUnit Rules for the approval process. (WO-200, AC-6)");

    /**
     * No class may call {@link EntityManager#createNativeQuery(String)} directly.
     *
     * <p>Calling this method with a runtime-constructed string bypasses the ORM's
     * parameterisation pipeline and creates SQL injection risk. Use Spring Data
     * {@code @Query} annotations with named parameters instead.
     */
    @ArchTest
    static final ArchRule entityManagerCreateNativeQueryMustNotBeCalled =
            noClasses()
                    .should().callMethodWhere(
                            DescribedPredicate.describe(
                                    "calls EntityManager.createNativeQuery",
                                    (JavaMethodCall call) ->
                                            call.getTargetOwner().isEquivalentTo(EntityManager.class)
                                            && call.getName().equals("createNativeQuery")
                            ))
                    .because("EntityManager.createNativeQuery bypasses ORM parameterisation "
                            + "and creates SQL injection risk; use @Query with named parameters "
                            + "or Spring Data query methods instead. (WO-200, AC-5)");

    /**
     * Proves the MessageDigest rule fires on the deliberately non-compliant fixture.
     */
    @Test
    void rule_firesOnDeprecatedCryptoUsage() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(DeprecatedCryptoUsage.class);

        ArchRule rule = noClasses()
                .should().callMethodWhere(
                        DescribedPredicate.describe(
                                "calls MessageDigest.getInstance",
                                (JavaMethodCall call) ->
                                        call.getTargetOwner().isEquivalentTo(MessageDigest.class)
                                        && call.getName().equals("getInstance")
                        ))
                .because("MessageDigest.getInstance is forbidden in application code");

        assertThatThrownBy(() -> rule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DeprecatedCryptoUsage");
    }

    /**
     * Proves the injection-risk rule fires on the deliberately non-compliant fixture.
     */
    @Test
    void rule_firesOnNativeQueryViolation() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(NativeQueryViolation.class);

        ArchRule rule = noClasses()
                .should().callMethodWhere(
                        DescribedPredicate.describe(
                                "calls EntityManager.createNativeQuery",
                                (JavaMethodCall call) ->
                                        call.getTargetOwner().isEquivalentTo(EntityManager.class)
                                        && call.getName().equals("createNativeQuery")
                        ))
                .because("EntityManager.createNativeQuery is forbidden");

        assertThatThrownBy(() -> rule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NativeQueryViolation");
    }
}
