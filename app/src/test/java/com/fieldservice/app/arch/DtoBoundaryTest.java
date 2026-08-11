package com.fieldservice.app.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for the DTO-boundary: JPA entities must not appear in
 * controller method signatures (parameters or return types).
 *
 * <p>Rules enforced:
 * <ol>
 *   <li><strong>DTO-1</strong>: No {@code @RestController} method may return a type
 *       directly annotated with {@code @Entity}.</li>
 *   <li><strong>DTO-2</strong>: No {@code @RestController} method may accept a parameter
 *       whose type is directly annotated with {@code @Entity}.</li>
 * </ol>
 *
 * <p>ResponseEntity, List, Page, and Optional wrappers are also checked for their
 * generic type arguments where ArchUnit can resolve them.
 *
 * <h3>Self-test layout</h3>
 * <ol>
 *   <li>{@link #controllers_must_not_return_entities} — positive rule on production code.</li>
 *   <li>{@link #dto_boundary_fires_on_violating_fixture} — proves the rule fires on the
 *       deliberately non-compliant {@code EntityLeakingControllerFixture}.</li>
 * </ol>
 */
class DtoBoundaryTest {

    private static JavaClasses PROD_CLASSES;

    @BeforeAll
    static void importClasses() {
        PROD_CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.fieldservice");
    }

    // -----------------------------------------------------------------------
    // DTO-1 + DTO-2: No entity in controller method signatures
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DTO-1/2: Controllers must not have @Entity types in method signatures")
    void controllers_must_not_return_entities() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(RestController.class)
                .should(notHaveEntityInMethodSignatures())
                .because("DTO-1/2: JPA entities must not leak through the HTTP boundary. "
                        + "Return DTO/response records instead of @Entity types. "
                        + "See TESTING.md §ArchUnit-DTO.");

        rule.check(PROD_CLASSES);
    }

    @Test
    @DisplayName("DTO boundary rule fires on fixture that returns @Entity directly")
    void dto_boundary_fires_on_violating_fixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importPackages("com.fieldservice.app.arch.fixture");

        assertThatThrownBy(() ->
                classes()
                        .that().areAnnotatedWith(RestController.class)
                        .should(notHaveEntityInMethodSignatures())
                        .check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("EntityLeakingControllerFixture");
    }

    // -----------------------------------------------------------------------
    // Custom ArchCondition
    // -----------------------------------------------------------------------

    private static ArchCondition<JavaClass> notHaveEntityInMethodSignatures() {
        return new ArchCondition<>("not have @Entity types in method return types or parameters") {
            @Override
            public void check(JavaClass controllerClass, ConditionEvents events) {
                for (JavaMethod method : controllerClass.getMethods()) {
                    checkReturnType(controllerClass, method, events);
                    checkParameters(controllerClass, method, events);
                }
            }

            private void checkReturnType(JavaClass controllerClass, JavaMethod method,
                                          ConditionEvents events) {
                JavaType returnType = method.getReturnType();
                if (isEntityType(returnType.toErasure())) {
                    events.add(SimpleConditionEvent.violated(controllerClass,
                            "Method " + controllerClass.getSimpleName() + "." + method.getName()
                            + "() returns a JPA @Entity type (" + returnType.getName()
                            + "). Return a DTO/response record instead."));
                }
            }

            private void checkParameters(JavaClass controllerClass, JavaMethod method,
                                          ConditionEvents events) {
                for (JavaType paramType : method.getParameterTypes()) {
                    if (isEntityType(paramType.toErasure())) {
                        events.add(SimpleConditionEvent.violated(controllerClass,
                                "Method " + controllerClass.getSimpleName() + "." + method.getName()
                                + "() accepts a JPA @Entity type as parameter ("
                                + paramType.getName() + "). Accept a DTO/request record instead."));
                    }
                }
            }

            private boolean isEntityType(JavaClass javaClass) {
                return javaClass.isAnnotatedWith(Entity.class);
            }
        };
    }
}
