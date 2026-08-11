package com.fieldservice.api.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Publishes the OpenAPI 3 contract for the Field Service API.
 *
 * <p>Registers:
 * <ul>
 *   <li>API info (title, description, version from configuration)</li>
 *   <li>A single configured server entry — set {@code API_PUBLIC_BASE_URL} in production</li>
 *   <li>Bearer JWT security scheme as a global requirement on all operations</li>
 *   <li>Shared component schemas: ErrorResponse, FieldError, PageMeta, PageLinks, PagedResponse</li>
 *   <li>Reusable Idempotency-Key header parameter component</li>
 * </ul>
 *
 * <p>Public operations (login, refresh, health) are allow-listed in {@link #PUBLIC_OPERATION_IDS}
 * and must NOT carry the bearer security requirement.
 */
@Configuration
public class OpenApiConfiguration {

    /** Bearer security scheme name — used by {@link StandardErrorResponsesCustomizer} and tests. */
    static final String SECURITY_SCHEME_NAME = "bearerAuth";

    /** Reusable header parameter component name for the Idempotency-Key header. */
    static final String IDEMPOTENCY_KEY_PARAM = "IdempotencyKey";

    /**
     * Operation IDs that are explicitly public and must NOT require bearer authentication.
     * Adding a new public endpoint without a matching entry here will fail the contract lint test.
     */
    static final Set<String> PUBLIC_OPERATION_IDS = Set.of(
            "login", "refresh", "logout", "streamTicket",
            "getHealth", "getInfo"
    );

    @Value("${app.api.version:0.1.0-SNAPSHOT}")
    private String apiVersion;

    @Value("${app.api.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Field Service Platform API")
                        .description(
                                "HTTP API for the Field Service Operations Platform. " +
                                "All endpoints require bearer JWT authentication unless explicitly allow-listed " +
                                "(login, refresh, health). " +
                                "Mutating operations require an Idempotency-Key header. " +
                                "All responses use the uniform ErrorResponse envelope for errors.")
                        .version(apiVersion))
                .servers(List.of(new Server()
                        .url(apiBaseUrl)
                        .description("Field Service API")))
                .components(new Components()
                        .addSchemas("ErrorResponse", errorResponseSchema())
                        .addSchemas("FieldError", fieldErrorSchema())
                        .addSchemas("PageMeta", pageMetaSchema())
                        .addSchemas("PageLinks", pageLinksSchema())
                        .addSchemas("PagedResponse", pagedResponseSchema())
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, bearerJwtScheme())
                        .addParameters(IDEMPOTENCY_KEY_PARAM, idempotencyKeyParameter()))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    private SecurityScheme bearerJwtScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("RS256-signed JWT access token. Obtain from POST /api/v1/auth/login. " +
                        "Send as: Authorization: Bearer <token>. TTL: 15 minutes.");
    }

    @SuppressWarnings("rawtypes")
    private io.swagger.v3.oas.models.parameters.Parameter idempotencyKeyParameter() {
        return new HeaderParameter()
                .name("Idempotency-Key")
                .description("Client-generated unique key (16–128 characters, alphanumeric and hyphens) " +
                        "ensuring at-most-once execution for mutating operations. " +
                        "Re-submitting with the same key within 24 hours replays the original response.")
                .required(true)
                .schema(new Schema<String>()
                        .type("string")
                        .minLength(16)
                        .maxLength(128)
                        .pattern("^[A-Za-z0-9_\\-]{16,128}$")
                        .example("req-2024-01-15-dispatch-00001"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> errorResponseSchema() {
        return new Schema<>()
                .type("object")
                .description("Uniform error envelope returned for all 4xx and 5xx responses.")
                .addProperty("code", new Schema<>().type("string")
                        .description("Stable machine-readable error code.")
                        .example("VALIDATION_FAILED"))
                .addProperty("message", new Schema<>().type("string")
                        .description("Human-readable description. Never contains PII or resource identifiers.")
                        .example("Request validation failed."))
                .addProperty("fieldErrors", new Schema<>().type("array")
                        .description("Per-field validation errors; empty list for non-validation errors.")
                        .items(new Schema<>().$ref("#/components/schemas/FieldError")))
                .addProperty("traceId", new Schema<>().type("string")
                        .description("Request trace identifier for log correlation.")
                        .example("none"))
                .addProperty("timestamp", new Schema<>().type("string").format("date-time")
                        .description("UTC timestamp of the error."))
                .required(List.of("code", "message", "traceId", "timestamp"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> fieldErrorSchema() {
        return new Schema<>()
                .type("object")
                .description("A single field-level validation error included in ErrorResponse.fieldErrors.")
                .addProperty("field", new Schema<>().type("string")
                        .description("Request field path, e.g. \"priority\" or \"description\".")
                        .example("priority"))
                .addProperty("message", new Schema<>().type("string")
                        .description("Why the field was rejected. Never contains the invalid value.")
                        .example("must not be blank"))
                .required(List.of("field", "message"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> pageMetaSchema() {
        return new Schema<>()
                .type("object")
                .description("Pagination metadata included in every PagedResponse.")
                .addProperty("number", new Schema<>().type("integer")
                        .description("Zero-based page number returned.")
                        .example(0))
                .addProperty("size", new Schema<>().type("integer")
                        .description("Effective page size (server-clamped to maximum 50).")
                        .example(20))
                .addProperty("totalElements", new Schema<>().type("integer").format("int64")
                        .description("Total matching elements. -1 in keyset (cursor) mode.")
                        .example(1234L))
                .addProperty("totalPages", new Schema<>().type("integer")
                        .description("Total pages at the effective size. -1 in keyset mode.")
                        .example(62))
                .addProperty("estimated", new Schema<>().type("boolean")
                        .description("true in keyset mode — counts are not exact. Omitted in offset mode."))
                .required(List.of("number", "size", "totalElements", "totalPages"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> pageLinksSchema() {
        return new Schema<>()
                .type("object")
                .description("Hypermedia navigation links included in every PagedResponse.")
                .addProperty("next", new Schema<>().type("string")
                        .description("Absolute URL of the next page; null on the last page.")
                        .example("http://localhost:8080/api/v1/work-orders?page=1&size=20"))
                .addProperty("prev", new Schema<>().type("string")
                        .description("Absolute URL of the previous page; null on page 0."));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> pagedResponseSchema() {
        return new Schema<>()
                .type("object")
                .description(
                        "Standard collection envelope. Every collection endpoint wraps its results in this type. " +
                        "The data array element type varies by resource.")
                .addProperty("data", new Schema<>().type("array")
                        .description("Page of results.")
                        .items(new Schema<>().type("object")))
                .addProperty("page", new Schema<>().$ref("#/components/schemas/PageMeta"))
                .addProperty("links", new Schema<>().$ref("#/components/schemas/PageLinks"))
                .required(List.of("data", "page", "links"));
    }
}
