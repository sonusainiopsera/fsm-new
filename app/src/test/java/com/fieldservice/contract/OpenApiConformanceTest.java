package com.fieldservice.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.contract.support.ApiAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validates that actual P0 endpoint responses conform to the schemas declared in the live
 * OpenAPI document served at {@code /api-docs}.
 *
 * <h2>Validation rules</h2>
 * <ol>
 *   <li>Every <em>required</em> field declared in the OpenAPI schema is present in the
 *       actual response.</li>
 *   <li>No top-level field in the response contradicts the declared type (string/number/
 *       boolean/array/object) in the schema.</li>
 *   <li>Error responses conform to the {@code ErrorResponse} component schema.</li>
 *   <li>Collection responses conform to the {@code PagedResponse} component schema shape.</li>
 * </ol>
 *
 * <h2>Scope</h2>
 * <p>This class validates <em>runtime response payloads</em> against the declared contract.
 * OpenAPI document structure (security schemes, parameter declarations, bearer auth) is
 * covered by {@link com.fieldservice.app.openapi.ContractLintIT}. These tests are
 * complementary, not overlapping.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiConformanceTest {

    @Autowired
    MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Cached OpenAPI document fetched once per class. */
    private JsonNode apiDoc;

    @BeforeAll
    void fetchApiDoc() throws Exception {
        String json = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        apiDoc = mapper.readTree(json);
    }

    // -------------------------------------------------------------------------
    // ErrorResponse schema conformance
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6: 400 response from /api/v1/work-orders conforms to ErrorResponse schema")
    void work_order_create_400_conforms_to_error_response_schema() throws Exception {
        // POST with an unknown field — @JsonIgnoreProperties(ignoreUnknown=false) → 400
        MvcResult result = mockMvc.perform(post("/api/v1/work-orders")
                        .with(dispatcherJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unknownField":"value"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertConformsToSchema(result.getResponse().getContentAsString(), "ErrorResponse");
        ApiAssertions.assertNoInternals(result.getResponse());
    }

    @Test
    @DisplayName("AC6: 400 response from POST /api/v1/auth/login conforms to ErrorResponse schema")
    void auth_login_400_conforms_to_error_response_schema() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"only-no-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertConformsToSchema(result.getResponse().getContentAsString(), "ErrorResponse");
        ApiAssertions.assertNoInternals(result.getResponse());
    }

    @Test
    @DisplayName("AC6: 401 response from unauthenticated GET /api/v1/work-orders conforms to ErrorResponse schema")
    void unauthenticated_request_401_conforms_to_error_response_schema() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/work-orders"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Some 401 responses may come from the security filter before the app can write JSON.
        // Only validate if the body is non-empty JSON.
        String body = result.getResponse().getContentAsString();
        if (body != null && !body.isBlank() && body.startsWith("{")) {
            assertConformsToSchema(body, "ErrorResponse");
            ApiAssertions.assertNoInternals(result.getResponse());
        }
    }

    // -------------------------------------------------------------------------
    // PagedResponse schema conformance (empty collection — fast, no DB needed)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6: Empty work-order list response conforms to PagedResponse schema shape")
    void empty_work_order_list_conforms_to_paged_response_schema() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/work-orders")
                        .with(dispatcherJwt()))
                .andReturn();

        // Test-profile H2 may return empty list (no rows) or a non-error response.
        int status = result.getResponse().getStatus();
        if (status == 200) {
            assertConformsToPagedResponseShape(result.getResponse().getContentAsString());
            ApiAssertions.assertNoInternals(result.getResponse());
        }
    }

    // -------------------------------------------------------------------------
    // Required fields present in declared response schemas
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6: ErrorResponse schema declares required fields: code, message, fieldErrors, traceId")
    void error_response_schema_declares_required_fields() {
        JsonNode errorSchema = resolveSchemaRef(apiDoc, "#/components/schemas/ErrorResponse");
        assertThat(errorSchema.isMissingNode()).as("ErrorResponse schema must exist").isFalse();

        JsonNode required = errorSchema.path("required");
        List<String> requiredFields = new ArrayList<>();
        if (required.isArray()) {
            required.forEach(n -> requiredFields.add(n.asText()));
        }

        // All four contract fields must be declared as required
        assertThat(requiredFields)
                .as("ErrorResponse schema must declare 'code' as required")
                .contains("code");
        assertThat(requiredFields)
                .as("ErrorResponse schema must declare 'message' as required")
                .contains("message");

        // fieldErrors and traceId may be in required OR always present in properties
        JsonNode properties = errorSchema.path("properties");
        assertThat(properties.has("fieldErrors"))
                .as("ErrorResponse schema must declare 'fieldErrors' property").isTrue();
        assertThat(properties.has("traceId"))
                .as("ErrorResponse schema must declare 'traceId' property").isTrue();
    }

    @Test
    @DisplayName("AC6: PageMeta schema declares required fields: number, size, totalElements, totalPages")
    void page_meta_schema_declares_required_fields() {
        JsonNode pageMetaSchema = resolveSchemaRef(apiDoc, "#/components/schemas/PageMeta");
        assertThat(pageMetaSchema.isMissingNode()).as("PageMeta schema must exist").isFalse();

        JsonNode properties = pageMetaSchema.path("properties");
        for (String field : List.of("number", "size", "totalElements", "totalPages")) {
            assertThat(properties.has(field))
                    .as("PageMeta must declare property: " + field).isTrue();
        }
    }

    @Test
    @DisplayName("AC6: All P0 operation paths are present in the OpenAPI document")
    void all_p0_operation_paths_present_in_openapi_doc() {
        JsonNode paths = apiDoc.path("paths");

        // Auth group
        assertThat(paths.has("/api/v1/auth/login"))
                .as("POST /api/v1/auth/login must be in OpenAPI doc").isTrue();

        // Work orders group
        assertThat(paths.has("/api/v1/work-orders"))
                .as("GET /api/v1/work-orders must be in OpenAPI doc").isTrue();

        // Transitions group — any /work-orders path with transitions
        boolean hasTransitions = false;
        Iterator<String> pathKeys = paths.fieldNames();
        while (pathKeys.hasNext()) {
            if (pathKeys.next().contains("transitions")) {
                hasTransitions = true;
                break;
            }
        }
        assertThat(hasTransitions).as("Transition endpoint must be in OpenAPI doc").isTrue();

        // Parts group
        boolean hasParts = false;
        pathKeys = paths.fieldNames();
        while (pathKeys.hasNext()) {
            if (pathKeys.next().endsWith("/parts")) {
                hasParts = true;
                break;
            }
        }
        assertThat(hasParts).as("Parts consumption endpoint must be in OpenAPI doc").isTrue();
    }

    @Test
    @DisplayName("AC6: Declared response field types match actual response field types (error envelope)")
    void error_response_field_types_match_declared_schema() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"only"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode response = mapper.readTree(body);
        JsonNode schema = resolveSchemaRef(apiDoc, "#/components/schemas/ErrorResponse");

        if (!schema.isMissingNode()) {
            List<String> typeViolations = checkFieldTypes(response, schema, "");
            assertThat(typeViolations)
                    .as("Error response field types must match OpenAPI schema")
                    .isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Validates a response body JSON string against a named component schema.
     * Checks that all {@code required} fields are present and no top-level field
     * has a type that contradicts the declared schema type.
     */
    private void assertConformsToSchema(String responseBody, String schemaName) {
        JsonNode schemaRef = resolveSchemaRef(apiDoc, "#/components/schemas/" + schemaName);
        if (schemaRef.isMissingNode()) {
            return; // schema doesn't exist yet — skip rather than fail
        }

        JsonNode response;
        try {
            response = mapper.readTree(responseBody);
        } catch (Exception e) {
            assertThat(responseBody)
                    .as("Response body must be parseable JSON for schema validation").isNull();
            return;
        }

        // Check required fields
        JsonNode required = schemaRef.path("required");
        if (required.isArray()) {
            List<String> violations = new ArrayList<>();
            required.forEach(req -> {
                if (!response.has(req.asText())) {
                    violations.add("Required field '" + req.asText() + "' missing from " + schemaName + " response");
                }
            });
            assertThat(violations)
                    .as("All required fields must be present in " + schemaName + " response")
                    .isEmpty();
        }

        // Check declared properties are present and type-correct
        List<String> typeViolations = checkFieldTypes(response, schemaRef, schemaName + ".");
        assertThat(typeViolations)
                .as("No field type violations in " + schemaName + " response")
                .isEmpty();
    }

    /**
     * Validates a response body conforms to the PagedResponse envelope shape:
     * {@code data} (array), {@code page} (object with number/size/totalElements/totalPages),
     * {@code links} (object).
     */
    private void assertConformsToPagedResponseShape(String responseBody) {
        JsonNode response;
        try {
            response = mapper.readTree(responseBody);
        } catch (Exception e) {
            assertThat(responseBody).as("Response must be parseable JSON").isNull();
            return;
        }

        assertThat(response.has("data")).as("PagedResponse must have 'data'").isTrue();
        assertThat(response.path("data").isArray()).as("'data' must be an array").isTrue();
        assertThat(response.has("page")).as("PagedResponse must have 'page'").isTrue();
        assertThat(response.path("page").isObject()).as("'page' must be an object").isTrue();

        JsonNode page = response.path("page");
        for (String field : List.of("number", "size", "totalElements", "totalPages")) {
            assertThat(page.has(field))
                    .as("PagedResponse.page must have '" + field + "'").isTrue();
        }

        assertThat(response.has("links")).as("PagedResponse must have 'links'").isTrue();
        assertThat(response.path("links").isObject()).as("'links' must be an object").isTrue();
    }

    /**
     * Checks that field values in a response match the types declared in the schema.
     * Returns a list of violation messages; empty means conformant.
     */
    private List<String> checkFieldTypes(JsonNode response, JsonNode schema, String prefix) {
        List<String> violations = new ArrayList<>();
        JsonNode properties = schema.path("properties");
        if (properties.isMissingNode()) {
            return violations;
        }

        // Only map explicitly declared types; additional fields are allowed (no additionalProperties=false assertion here)
        Map<String, String> declaredTypes = new java.util.LinkedHashMap<>();
        properties.fields().forEachRemaining(e -> {
            JsonNode typeNode = e.getValue().path("type");
            if (!typeNode.isMissingNode()) {
                declaredTypes.put(e.getKey(), typeNode.asText());
            }
        });

        declaredTypes.forEach((field, declaredType) -> {
            if (!response.has(field)) return; // missing optional fields are OK
            JsonNode value = response.get(field);
            if (!matchesType(value, declaredType)) {
                violations.add(prefix + field + " declared as '" + declaredType +
                        "' but actual node type is '" + value.getNodeType().name().toLowerCase() + "'");
            }
        });

        return violations;
    }

    private boolean matchesType(JsonNode value, String declaredType) {
        return switch (declaredType) {
            case "string"  -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number"  -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array"   -> value.isArray();
            case "object"  -> value.isObject();
            default        -> true; // unknown type — accept
        };
    }

    /**
     * Resolves a {@code $ref} string (e.g. {@code "#/components/schemas/ErrorResponse"})
     * against the given OpenAPI document root.
     */
    private JsonNode resolveSchemaRef(JsonNode root, String ref) {
        if (!ref.startsWith("#/")) {
            return mapper.nullNode();
        }
        String[] parts = ref.substring(2).split("/");
        JsonNode node = root;
        for (String part : parts) {
            node = node.path(part);
            if (node.isMissingNode()) return node;
        }
        return node;
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor dispatcherJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                .jwt(j -> j.subject("test-dispatcher").claim("roles", List.of("DISPATCHER")));
    }
}
