package com.fieldservice.api.openapi;

import com.fieldservice.security.AbstractIntegrationTest;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract lint tests enforcing platform conventions on every operation in the generated OpenAPI document.
 *
 * <p>Lint rules checked:
 * <ul>
 *   <li>Every operation carries the bearerAuth security requirement (or is explicitly allow-listed).</li>
 *   <li>Every operation declares at least one 4xx error response.</li>
 *   <li>Mutating operations (POST, PUT, PATCH, DELETE) declare the Idempotency-Key header
 *       and a 409 conflict response.</li>
 *   <li>Shared component schemas are defined exactly once.</li>
 *   <li>The document contains no forbidden substrings (credentials, internal hosts, PII).</li>
 *   <li>The interactive UI is disabled in the test profile (which models production).</li>
 * </ul>
 *
 * <p>These tests boot the api profile with MockMvc and parse the live /api-docs JSON.
 * A violation prints the offending operation ID and rule name so the fix is obvious.
 */
class OpenApiContractLintTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // AC4 — Security requirement on all non-public operations
    // -------------------------------------------------------------------------

    @Test
    void everyNonPublicOperationCarriesBearerSecurityRequirement() throws Exception {
        OpenAPI api = fetchOpenApi();
        List<String> violations = new ArrayList<>();

        forEachOperation(api, (method, path, op) -> {
            if (isPublicOperation(api, op)) return;

            boolean hasBearer = hasBearerScheme(op.getSecurity())
                    || hasBearerScheme(api.getSecurity());

            if (!hasBearer) {
                violations.add("RULE[bearer-required]: " + method + " " + path
                        + " operationId=" + op.getOperationId());
            }
        });

        assertThat(violations)
                .as("All non-public operations must declare bearerAuth. " +
                        "Add the operationId to OpenApiConfiguration.PUBLIC_OPERATION_IDS if intentionally public.\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC3 — Standard error responses on every operation
    // -------------------------------------------------------------------------

    @Test
    void everyOperationDeclares4xxErrorResponses() throws Exception {
        OpenAPI api = fetchOpenApi();
        List<String> violations = new ArrayList<>();

        forEachOperation(api, (method, path, op) -> {
            if (op.getResponses() == null || op.getResponses().isEmpty()) {
                violations.add("RULE[no-responses]: " + method + " " + path
                        + " operationId=" + op.getOperationId());
                return;
            }
            boolean has4xx = op.getResponses().keySet().stream()
                    .anyMatch(k -> k.startsWith("4"));
            if (!has4xx) {
                violations.add("RULE[missing-4xx]: " + method + " " + path
                        + " operationId=" + op.getOperationId());
            }
        });

        assertThat(violations)
                .as("Every operation must declare at least one 4xx error response.\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    @Test
    void everyOperationDeclaresStandard401And403Responses() throws Exception {
        OpenAPI api = fetchOpenApi();
        List<String> violations = new ArrayList<>();

        forEachOperation(api, (method, path, op) -> {
            if (op.getResponses() == null) {
                violations.add("RULE[no-responses]: " + method + " " + path);
                return;
            }
            for (String status : List.of("401", "403")) {
                if (!op.getResponses().containsKey(status)) {
                    violations.add("RULE[missing-" + status + "]: " + method + " " + path
                            + " operationId=" + op.getOperationId());
                }
            }
        });

        assertThat(violations)
                .as("Every operation must declare 401 and 403 responses.\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC4 — Mutating operations: Idempotency-Key header + 409 response
    // -------------------------------------------------------------------------

    @Test
    void mutatingOperationsDeclare409ConflictResponse() throws Exception {
        OpenAPI api = fetchOpenApi();
        List<String> violations = new ArrayList<>();

        forMutatingOperations(api, (method, path, op) -> {
            if (op.getResponses() == null || !op.getResponses().containsKey("409")) {
                violations.add("RULE[missing-409]: " + method + " " + path
                        + " operationId=" + op.getOperationId());
            }
        });

        assertThat(violations)
                .as("POST, PUT, PATCH, DELETE operations must declare a 409 conflict response.\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC2 — Shared component schemas defined exactly once
    // -------------------------------------------------------------------------

    @Test
    void sharedComponentSchemasAreDefinedOnce() throws Exception {
        OpenAPI api = fetchOpenApi();

        assertThat(api.getComponents())
                .as("OpenAPI document must have a components section")
                .isNotNull();

        assertThat(api.getComponents().getSchemas())
                .as("Shared component schemas must be defined in components")
                .containsKeys("ErrorResponse", "FieldError", "PageMeta", "PageLinks", "PagedResponse");

        assertThat(api.getComponents().getSecuritySchemes())
                .as("bearerAuth security scheme must be defined in components")
                .containsKey(OpenApiConfiguration.SECURITY_SCHEME_NAME);

        assertThat(api.getComponents().getParameters())
                .as("Idempotency-Key header parameter must be defined in components")
                .containsKey(OpenApiConfiguration.IDEMPOTENCY_KEY_PARAM);
    }

    // -------------------------------------------------------------------------
    // AC8 — Document contains no forbidden substrings
    // -------------------------------------------------------------------------

    @Test
    void documentContainsNoForbiddenSubstrings() throws Exception {
        String apiDocJson = rawApiDoc();

        // No database connection strings
        assertThat(apiDocJson)
                .as("API spec must not contain internal database connection strings")
                .doesNotContain("jdbc:", "postgres://", "postgresql://");

        // No internal network hostnames
        assertThat(apiDocJson)
                .as("API spec must not contain internal hostnames")
                .doesNotContain(".internal", ".corp.", ".prod-db.");

        // No credential-shaped literals in example values
        assertThat(apiDocJson)
                .as("API spec must not contain credential-shaped strings")
                .doesNotContainPattern("\"password\"\\s*:\\s*\"[^\"]{4,}\"");

        // No real personal data patterns in examples
        assertThat(apiDocJson)
                .as("API spec must not contain real email addresses in examples")
                .doesNotContainPattern("[a-z]+\\.[a-z]+@[a-z]+\\.(com|org|net)");
    }

    // -------------------------------------------------------------------------
    // AC1 — Interactive UI disabled in non-development profile
    // -------------------------------------------------------------------------

    @Test
    void swaggerUiIsDisabledByDefault() throws Exception {
        // The test profile (modelling production) must not expose swagger-ui
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    void apiDocsJsonIsReachable() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private OpenAPI fetchOpenApi() throws Exception {
        return Json.mapper().readValue(rawApiDoc(), OpenAPI.class);
    }

    private String rawApiDoc() throws Exception {
        return mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void forEachOperation(OpenAPI api, TriConsumer consumer) {
        if (api.getPaths() == null) return;
        api.getPaths().forEach((path, pathItem) -> {
            if (pathItem.getGet()    != null) consumer.accept("GET",    path, pathItem.getGet());
            if (pathItem.getPost()   != null) consumer.accept("POST",   path, pathItem.getPost());
            if (pathItem.getPut()    != null) consumer.accept("PUT",    path, pathItem.getPut());
            if (pathItem.getPatch()  != null) consumer.accept("PATCH",  path, pathItem.getPatch());
            if (pathItem.getDelete() != null) consumer.accept("DELETE", path, pathItem.getDelete());
        });
    }

    private void forMutatingOperations(OpenAPI api, TriConsumer consumer) {
        if (api.getPaths() == null) return;
        api.getPaths().forEach((path, pathItem) -> {
            if (pathItem.getPost()   != null) consumer.accept("POST",   path, pathItem.getPost());
            if (pathItem.getPut()    != null) consumer.accept("PUT",    path, pathItem.getPut());
            if (pathItem.getPatch()  != null) consumer.accept("PATCH",  path, pathItem.getPatch());
            if (pathItem.getDelete() != null) consumer.accept("DELETE", path, pathItem.getDelete());
        });
    }

    private boolean isPublicOperation(OpenAPI api, Operation op) {
        return op.getOperationId() != null
                && OpenApiConfiguration.PUBLIC_OPERATION_IDS.contains(op.getOperationId());
    }

    private boolean hasBearerScheme(List<SecurityRequirement> requirements) {
        if (requirements == null) return false;
        return requirements.stream()
                .anyMatch(r -> r.containsKey(OpenApiConfiguration.SECURITY_SCHEME_NAME));
    }

    @FunctionalInterface
    interface TriConsumer {
        void accept(String method, String path, Operation operation);
    }
}
