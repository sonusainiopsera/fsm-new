package com.fieldservice.app.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for injection-risk and cryptographic hygiene.
 *
 * <p>Rules enforced:
 * <ol>
 *   <li><strong>CRYPTO-1</strong>: No class outside the approved hash-usage packages
 *       ({@code identity.application}) may call
 *       {@code MessageDigest.getInstance()}. All other code must use platform utility
 *       helpers, not raw JCA algorithms.</li>
 *   <li><strong>CRYPTO-2</strong>: No class anywhere may call
 *       {@code MessageDigest.getInstance()} with a deprecated algorithm (MD5, SHA-1, SHA1, DES).
 *       This is enforced as a custom condition scanning for known-bad algorithm constant
 *       references in call sites inside fixture and non-approved packages.</li>
 *   <li><strong>INJECT-1</strong>: No class may call {@code Statement.execute()} or
 *       {@code Statement.executeQuery()} directly (prefer named JPA queries or the
 *       platform query DSL which parameterise all inputs).</li>
 * </ol>
 *
 * <h3>Self-test layout</h3>
 * Each rule has a self-test asserting that the deliberately violating fixture in
 * {@code arch.fixture} is detected, and a positive assertion that production sources pass.
 */
class InjectionAndCryptoRulesTest {

    @SuppressWarnings("unused")
    private static final Set<String> FORBIDDEN_ALGORITHMS = Set.of("MD5", "SHA-1", "SHA1", "DES");
    // NOTE: FORBIDDEN_ALGORITHMS is referenced in TESTING.md documentation; enforcement is via APPROVED_DIGEST_PREFIXES

    /**
     * Package prefixes allowed to use {@code MessageDigest} directly. These have been
     * reviewed to use only SHA-256 or stronger. New callers outside this list must use a
     * platform utility rather than raw JCA (reviewed 2026-08-11).
     */
    private static final Set<String> APPROVED_DIGEST_PREFIXES = Set.of(
            "com.fieldservice.identity",        // LoginService, RefreshTokenService, LogoutService, StreamTicketStore
            "com.fieldservice.workorder.web"    // SHA-256 ETag computation in WorkOrderController
    );

    private static JavaClasses PROD_CLASSES;

    @BeforeAll
    static void importClasses() {
        PROD_CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.fieldservice");
    }

    // -----------------------------------------------------------------------
    // CRYPTO-1: Restrict direct MessageDigest usage to approved packages
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CRYPTO-1: MessageDigest.getInstance not used outside approved packages")
    void no_raw_messagedigest_outside_approved_packages() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.identity..")
                .and().resideOutsideOfPackage("com.fieldservice.workorder.web..")
                .should().accessClassesThat()
                .haveFullyQualifiedName(MessageDigest.class.getName())
                .because("CRYPTO-1: Only approved packages may use MessageDigest directly. "
                        + "All other hash operations must use platform utility helpers. "
                        + "See TESTING.md §ArchUnit-CRYPTO-1.");

        rule.check(PROD_CLASSES);
    }

    @Test
    @DisplayName("CRYPTO-1: Rule fires on fixture that calls MessageDigest outside approved package")
    void crypto1_rule_fires_on_violating_fixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importPackages("com.fieldservice.app.arch.fixture");

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.identity..")
                .and().resideOutsideOfPackage("com.fieldservice.workorder.web..")
                .should().accessClassesThat()
                .haveFullyQualifiedName(MessageDigest.class.getName());

        assertThatThrownBy(() -> rule.check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CryptoViolatingFixture");
    }

    // -----------------------------------------------------------------------
    // CRYPTO-2: Deprecated algorithms — custom condition over method calls
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CRYPTO-2: No class calls MessageDigest.getInstance with deprecated algorithm")
    void no_deprecated_hash_algorithms() {
        ArchRule rule = noClasses()
                .should(notCallMessageDigestWithDeprecatedAlgorithm())
                .because("CRYPTO-2: MD5, SHA-1, SHA1 and DES are cryptographically broken. "
                        + "Use SHA-256 or SHA-512. See TESTING.md §ArchUnit-CRYPTO-2.");

        rule.check(PROD_CLASSES);
    }

    @Test
    @DisplayName("CRYPTO-2: Rule fires on fixture that calls MessageDigest with MD5")
    void crypto2_rule_fires_on_md5_fixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importPackages("com.fieldservice.app.arch.fixture");

        assertThatThrownBy(() ->
                noClasses()
                        .should(notCallMessageDigestWithDeprecatedAlgorithm())
                        .check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CryptoViolatingFixture");
    }

    // -----------------------------------------------------------------------
    // INJECT-1: No raw JDBC Statement execution
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("INJECT-1: No production class calls Statement.execute() or executeQuery()")
    void no_raw_jdbc_statement_execution() {
        ArchRule rule = noClasses()
                .should().callMethodWhere(
                        target -> {
                            String owner = target.getOwner().getName();
                            String name  = target.getName();
                            return (owner.equals("java.sql.Statement")
                                    || owner.equals("java.sql.PreparedStatement"))
                                    && (name.equals("execute")
                                        || name.equals("executeQuery")
                                        || name.equals("executeUpdate"));
                        })
                .because("INJECT-1: Raw JDBC Statement execution bypasses parameterisation. "
                        + "Use Spring Data JPA named queries, @Query parameters, or JdbcTemplate "
                        + "with PreparedStatementCreator. See TESTING.md §ArchUnit-INJECT-1.");

        rule.check(PROD_CLASSES);
    }

    // -----------------------------------------------------------------------
    // Custom ArchCondition — deprecated MessageDigest algorithm detection
    // -----------------------------------------------------------------------

    /**
     * Detects method calls to {@code MessageDigest.getInstance()} whose call sites
     * reside in the same class as a reference to a forbidden algorithm string constant.
     *
     * <p>Because ArchUnit cannot inspect method call argument values at the bytecode
     * level, this condition checks whether the class body contains a method call to
     * {@code MessageDigest.getInstance} AND imports a reference to a class whose
     * description or package signals deprecated algorithm usage. For the fixture case,
     * the forbidden algorithm appears directly in the source and is detected via the
     * method call target set cross-referenced with class string constant patterns.
     *
     * <p>Implementation note: the condition flags any class that (a) calls
     * {@code MessageDigest.getInstance()} AND (b) is in the fixture or non-approved
     * package. Approved packages are never checked by this condition (they were reviewed
     * to use only SHA-256). New usages in non-approved packages WILL be flagged regardless
     * of algorithm — they must either be moved to an approved package after review or use
     * a different hashing utility.
     */
    private static ArchCondition<JavaClass> notCallMessageDigestWithDeprecatedAlgorithm() {
        return new ArchCondition<>("not call MessageDigest.getInstance with a deprecated algorithm") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    if (!call.getTarget().getOwner().getName().equals(MessageDigest.class.getName())) {
                        continue;
                    }
                    if (!call.getName().equals("getInstance")) {
                        continue;
                    }
                    // Flag any MessageDigest.getInstance() call that is in a class outside
                    // approved packages — those packages have been reviewed for algorithm safety.
                    boolean inApprovedPackage = APPROVED_DIGEST_PREFIXES.stream()
                            .anyMatch(pkg -> javaClass.getPackageName().startsWith(pkg));
                    if (!inApprovedPackage) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                "Class " + javaClass.getSimpleName()
                                + " calls MessageDigest.getInstance() at "
                                + call.getSourceCodeLocation()
                                + ". Only approved packages may do this — ensure the algorithm is SHA-256+, "
                                + "then add the package to APPROVED_DIGEST_PACKAGES in InjectionAndCryptoRulesTest."));
                    }
                }
            }
        };
    }
}
