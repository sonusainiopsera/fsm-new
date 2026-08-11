package com.fieldservice.workorder.api;

import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based API surface test asserting that no PUT or PATCH endpoint accepts a DTO
 * with a {@code state} or {@code status} field.
 *
 * <p>Enforces the constraint from WO-124: the event-based transitions endpoint is the
 * ONLY lifecycle mutation surface; direct state assignment via an update DTO is forbidden.
 */
class NoMutableStateEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void noPutOrPatchEndpointBindsDtoWithStateOrStatusField() {
        List<String> violations = new ArrayList<>();

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            var handler = entry.getValue();

            if (info.getMethodsCondition() == null) {
                continue;
            }
            boolean isMutable = info.getMethodsCondition().getMethods().stream()
                    .anyMatch(m -> m == RequestMethod.PUT || m == RequestMethod.PATCH);
            if (!isMutable) {
                continue;
            }

            for (MethodParameter param : handler.getMethodParameters()) {
                if (!param.hasParameterAnnotation(RequestBody.class)) {
                    continue;
                }
                Class<?> dtoType = param.getParameterType();
                checkFields(dtoType, info.toString(), violations);
            }
        }

        assertThat(violations)
                .as("PUT/PATCH DTOs must not expose 'state' or 'status' fields — use POST /transitions instead")
                .isEmpty();
    }

    private void checkFields(Class<?> type, String endpoint, List<String> violations) {
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            if (name.equals("state") || name.equals("status")) {
                violations.add("Endpoint " + endpoint + " DTO " + type.getSimpleName()
                        + " contains forbidden field '" + field.getName() + "'");
            }
        }
        // also check record components (getDeclaredFields covers them for records)
    }
}
