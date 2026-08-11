package com.fieldservice.workorder.api;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Reflection-based test verifying that no PUT or PATCH endpoint in the API can accept a
 * {@code state} or {@code status} field on a request DTO, enforcing the invariant that
 * {@code POST /api/v1/work-orders/{id}/transitions} is the only lifecycle mutation surface.
 */
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class NoMutableStateEndpointTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void no_put_or_patch_endpoint_exists_in_the_api() {
        Map<RequestMappingInfo, HandlerMethod> methods = handlerMapping.getHandlerMethods();

        long putPatchCount = methods.keySet().stream()
                .filter(info -> {
                    Set<org.springframework.web.bind.annotation.RequestMethod> httpMethods =
                            info.getMethodsCondition().getMethods();
                    return httpMethods.contains(org.springframework.web.bind.annotation.RequestMethod.PUT)
                            || httpMethods.contains(org.springframework.web.bind.annotation.RequestMethod.PATCH);
                })
                .count();

        assertThat(putPatchCount)
                .as("No PUT or PATCH endpoints should exist — state changes go through POST /transitions")
                .isZero();
    }

    @Test
    void no_request_body_dto_on_post_endpoints_exposes_state_or_status_field() {
        Map<RequestMappingInfo, HandlerMethod> methods = handlerMapping.getHandlerMethods();
        Set<String> forbidden = Set.of("state", "status");

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : methods.entrySet()) {
            Set<org.springframework.web.bind.annotation.RequestMethod> httpMethods =
                    entry.getKey().getMethodsCondition().getMethods();
            if (!httpMethods.contains(org.springframework.web.bind.annotation.RequestMethod.POST)) {
                continue;
            }

            String pattern = entry.getKey().getPatternValues().stream().findFirst().orElse("");
            // Skip the legitimate transitions endpoint — it accepts 'event', not 'state'/'status'
            if (pattern.endsWith("/transitions")) {
                continue;
            }

            HandlerMethod handlerMethod = entry.getValue();
            for (Parameter param : handlerMethod.getMethod().getParameters()) {
                if (param.isAnnotationPresent(RequestBody.class)) {
                    assertDtoHasNoStateOrStatusField(param.getType(), forbidden, pattern);
                }
            }
        }
    }

    private static void assertDtoHasNoStateOrStatusField(Class<?> dtoType, Set<String> forbidden, String endpoint) {
        for (Field field : allFieldsOf(dtoType)) {
            if (forbidden.contains(field.getName().toLowerCase())) {
                fail("Endpoint %s has a @RequestBody DTO %s with forbidden field '%s'. "
                        + "Use POST /transitions instead.",
                        endpoint, dtoType.getSimpleName(), field.getName());
            }
        }
    }

    private static Field[] allFieldsOf(Class<?> type) {
        if (type == null || type == Object.class) return new Field[0];
        Field[] own = type.getDeclaredFields();
        Field[] parent = allFieldsOf(type.getSuperclass());
        Field[] combined = Arrays.copyOf(own, own.length + parent.length);
        System.arraycopy(parent, 0, combined, own.length, parent.length);
        return combined;
    }
}
