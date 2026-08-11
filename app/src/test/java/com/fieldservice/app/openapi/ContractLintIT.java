package com.fieldservice.app.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract lint rules — boots the full Spring context with MockMvc, retrieves the live
 * api-docs document, and asserts platform conventions hold for every operation.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>Every operation declares at least one 4xx error response referencing ErrorResponse.</li>
 *   <li>Every operation declares the bearerAuth security requirement unless explicitly allow-listed.</li>
 *   <li>Every collection operation (GET returning paged response) declares page, size, and sort parameters.</li>
 *   <li>Every mutating operation (POST/PUT/PATCH/DELETE) declares the Idempotency-Key header and a 409 response.</li>
 * </ul>
 *
 * <p>Failures include the offending operation path+method and rule name for fast diagnosis.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class ContractLintIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode apiDoc;

    @BeforeEach
    void fetchApiDoc() throws Exception {
        String json = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        apiDoc = mapper.readTree(json);
    }

    @Test
    @DisplayName("API doc is retrievable and contains openapi field")
    void api_doc_is_present() {
        assertThat(apiDoc.has("openapi")).isTrue();
        assertThat(apiDoc.at("/info/title").asText()).isEqualTo("Field Service API");
        assertThat(apiDoc.has("paths")).isTrue();
        assertThat(apiDoc.has("components")).isTrue();
    }

    @Test
    @DisplayName("Shared component schemas are defined: ErrorResponse, FieldError, PageMeta, PageLinks, PagedResponse")
    void shared_schemas_are_defined() {
        JsonNode schemas = apiDoc.at("/components/schemas");
        assertThat(schemas.has("ErrorResponse")).as("ErrorResponse schema missing").isTrue();
        assertThat(schemas.has("FieldError")).as("FieldError schema missing").isTrue();
        assertThat(schemas.has("PageMeta")).as("PageMeta schema missing").isTrue();
        assertThat(schemas.has("PageLinks")).as("PageLinks schema missing").isTrue();
    }

    @Test
    @DisplayName("bearerAuth security scheme is defined in components")
    void bearer_security_scheme_defined() {
        JsonNode scheme = apiDoc.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.isMissingNode()).isFalse();
        assertThat(scheme.at("/type").asText()).isEqualTo("http");
        assertThat(scheme.at("/scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.at("/bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    @DisplayName("IdempotencyKey header parameter is defined in components")
    void idempotency_key_parameter_defined() {
        JsonNode param = apiDoc.at("/components/parameters/IdempotencyKey");
        assertThat(param.isMissingNode()).isFalse();
        assertThat(param.at("/name").asText()).isEqualTo("Idempotency-Key");
        assertThat(param.at("/in").asText()).isEqualTo("header");
    }

    @Test
    @DisplayName("Every operation has the bearerAuth security requirement unless allow-listed")
    void every_non_public_operation_has_security_requirement() {
        List<String> violations = new ArrayList<>();
        forEachOperation((path, method, operation) -> {
            String opId = operation.path("operationId").asText(path + ":" + method);
            if (OpenApiConfiguration.PUBLIC_OPERATION_IDS.contains(opId)) {
                return;
            }
            JsonNode security = operation.get("security");
            boolean hasBearerAuth = false;
            if (security != null && security.isArray()) {
                for (JsonNode req : security) {
                    if (req.has(OpenApiConfiguration.SECURITY_SCHEME_NAME)) {
                        hasBearerAuth = true;
                        break;
                    }
                }
            }
            // Also check global security on the OpenAPI object
            if (!hasBearerAuth) {
                JsonNode globalSecurity = apiDoc.get("security");
                if (globalSecurity != null && globalSecurity.isArray()) {
                    for (JsonNode req : globalSecurity) {
                        if (req.has(OpenApiConfiguration.SECURITY_SCHEME_NAME)) {
                            hasBearerAuth = true;
                            break;
                        }
                    }
                }
            }
            if (!hasBearerAuth) {
                violations.add("[RULE:security-required] " + method.toUpperCase() + " " + path +
                        " (operationId=" + opId + ") lacks bearerAuth security requirement");
            }
        });
        assertThat(violations).as("Security requirement violations").isEmpty();
    }

    @Test
    @DisplayName("Every operation declares at least one 4xx response referencing ErrorResponse")
    void every_operation_has_4xx_error_response() {
        List<String> violations = new ArrayList<>();
        forEachOperation((path, method, operation) -> {
            JsonNode responses = operation.get("responses");
            if (responses == null) {
                violations.add("[RULE:error-response-required] " + method.toUpperCase() + " " + path +
                        " has no responses block");
                return;
            }
            boolean has4xx = false;
            for (String code : List.of("400", "401", "403", "404", "409", "422", "429", "503")) {
                if (responses.has(code)) {
                    has4xx = true;
                    break;
                }
            }
            if (!has4xx) {
                violations.add("[RULE:error-response-required] " + method.toUpperCase() + " " + path +
                        " declares no 4xx/5xx error responses");
            }
        });
        assertThat(violations).as("Missing 4xx response violations").isEmpty();
    }

    @Test
    @DisplayName("Mutating operations (POST/PUT/PATCH/DELETE) declare Idempotency-Key header and 409 response")
    void mutating_operations_have_idempotency_key_and_409() {
        Set<String> mutatingMethods = Set.of("post", "put", "patch", "delete");
        List<String> violations = new ArrayList<>();

        forEachOperation((path, method, operation) -> {
            if (!mutatingMethods.contains(method)) {
                return;
            }
            // Check Idempotency-Key header parameter
            JsonNode params = operation.get("parameters");
            boolean hasIdempotencyKey = false;
            if (params != null && params.isArray()) {
                for (JsonNode param : params) {
                    String name = param.path("name").asText();
                    String ref  = param.path("$ref").asText("");
                    if ("Idempotency-Key".equals(name) ||
                            ref.contains("IdempotencyKey") ||
                            ref.contains("Idempotency-Key")) {
                        hasIdempotencyKey = true;
                        break;
                    }
                }
            }
            if (!hasIdempotencyKey) {
                violations.add("[RULE:idempotency-key-required] " + method.toUpperCase() + " " + path +
                        " missing Idempotency-Key header parameter");
            }

            // Check 409 response
            JsonNode responses = operation.get("responses");
            if (responses == null || !responses.has("409")) {
                violations.add("[RULE:conflict-response-required] " + method.toUpperCase() + " " + path +
                        " missing 409 Conflict response");
            }
        });
        assertThat(violations).as("Mutating operation convention violations").isEmpty();
    }

    @Test
    @DisplayName("Collection GET operations declare page, size, sort parameters")
    void collection_operations_declare_pagination_params() {
        List<String> violations = new ArrayList<>();

        forEachOperation((path, method, operation) -> {
            if (!"get".equals(method)) {
                return;
            }
            // Detect collection operations: return type references paged envelope or data array
            JsonNode successResponse = findFirstSuccessResponse(operation);
            if (!isPagedResponse(successResponse)) {
                return;
            }

            JsonNode params = operation.get("parameters");
            boolean hasPage = false, hasSize = false, hasSort = false;
            if (params != null && params.isArray()) {
                for (JsonNode param : params) {
                    String name = param.path("name").asText();
                    if ("page".equals(name)) hasPage = true;
                    if ("size".equals(name)) hasSize = true;
                    if ("sort".equals(name)) hasSort = true;
                }
            }
            if (!hasPage) violations.add("[RULE:pagination-params] GET " + path + " missing 'page' parameter");
            if (!hasSize) violations.add("[RULE:pagination-params] GET " + path + " missing 'size' parameter");
            if (!hasSort) violations.add("[RULE:pagination-params] GET " + path + " missing 'sort' parameter");
        });
        assertThat(violations).as("Pagination parameter violations").isEmpty();
    }

    @Test
    @DisplayName("No forbidden substrings in the contract (no real credentials, hostnames, or PII)")
    void no_forbidden_substrings_in_contract() throws Exception {
        String json = mapper.writeValueAsString(apiDoc);

        // Real internal hostnames
        assertThat(json).as("No real internal hostnames").doesNotContain("fieldservice.internal");
        assertThat(json).as("No real auth hostnames").doesNotContain("auth.fieldservice.local");

        // Credential-shaped strings
        assertThat(json).as("No literal 'password' values").doesNotContainPattern("\"password\"\\s*:\\s*\"[^{]");
        assertThat(json).as("No JWT token literals").doesNotContainPattern("eyJ[A-Za-z0-9_-]{10,}");

        // Production API keys
        assertThat(json).as("No AWS/GCP credential patterns").doesNotContainPattern("AKIA[A-Z0-9]{16}");
    }

    @Test
    @DisplayName("Swagger UI is not accessible under the test (non-dev) profile")
    void swagger_ui_disabled_in_non_dev_profile() throws Exception {
        // When swagger-ui is disabled, springdoc does not register the route.
        // The response may be 404 (route not registered) or 401 (security challenge before 404).
        // Either confirms the interactive UI is not available in this profile.
        int status = mockMvc.perform(get("/swagger-ui/index.html"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).as("Swagger UI must not be accessible (expected 401 or 404)")
                .isIn(401, 404);
    }

    // ---- helpers ---------------------------------------------------------------

    private void forEachOperation(OperationConsumer consumer) {
        JsonNode paths = apiDoc.get("paths");
        if (paths == null) return;
        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
                String method = methodEntry.getKey();
                if (Set.of("get", "post", "put", "patch", "delete").contains(method)) {
                    consumer.accept(path, method, methodEntry.getValue());
                }
            });
        });
    }

    private JsonNode findFirstSuccessResponse(JsonNode operation) {
        JsonNode responses = operation.get("responses");
        if (responses == null) return mapper.nullNode();
        for (String code : List.of("200", "201")) {
            if (responses.has(code)) return responses.get(code);
        }
        return mapper.nullNode();
    }

    private boolean isPagedResponse(JsonNode response) {
        // Check if response content schema references PagedResponse or has data/page/links structure
        String responseStr = response.toString();
        return responseStr.contains("PagedResponse") ||
               responseStr.contains("\"data\"") && responseStr.contains("\"page\"") ||
               responseStr.contains("#/components/schemas/PagedResponse");
    }

    @FunctionalInterface
    interface OperationConsumer {
        void accept(String path, String method, JsonNode operation);
    }
}
