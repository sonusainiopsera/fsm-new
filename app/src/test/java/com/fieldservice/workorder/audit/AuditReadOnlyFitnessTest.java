package com.fieldservice.workorder.audit;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fitness test asserting the read-only invariant on the work order history API.
 *
 * <p>Verifies that no HTTP verb other than GET is registered under the revision history and
 * timeline paths. This makes it structurally impossible to mutate or delete audit records
 * through the application layer.
 */
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class AuditReadOnlyFitnessTest {

    private static final Set<RequestMethod> MUTATING_VERBS = Set.of(
            RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("no mutating HTTP verb is registered on /revisions paths")
    void no_mutating_verb_on_revisions_path() {
        Map<RequestMappingInfo, ?> handlers = handlerMapping.getHandlerMethods();

        long violatingCount = handlers.keySet().stream()
                .filter(info -> info.getPatternValues().stream()
                        .anyMatch(p -> p.endsWith("/revisions")))
                .filter(info -> info.getMethodsCondition().getMethods().stream()
                        .anyMatch(MUTATING_VERBS::contains))
                .count();

        assertThat(violatingCount)
                .as("No mutating verb should be registered on any /revisions path")
                .isZero();
    }

    @Test
    @DisplayName("no mutating HTTP verb is registered on /timeline paths")
    void no_mutating_verb_on_timeline_path() {
        Map<RequestMappingInfo, ?> handlers = handlerMapping.getHandlerMethods();

        long violatingCount = handlers.keySet().stream()
                .filter(info -> info.getPatternValues().stream()
                        .anyMatch(p -> p.endsWith("/timeline")))
                .filter(info -> info.getMethodsCondition().getMethods().stream()
                        .anyMatch(MUTATING_VERBS::contains))
                .count();

        assertThat(violatingCount)
                .as("No mutating verb should be registered on any /timeline path")
                .isZero();
    }
}
