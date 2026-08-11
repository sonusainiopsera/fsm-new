package com.fieldservice.app.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Publishes the OpenAPI 3 contract for the Field Service API.
 *
 * <p>Defines shared component schemas once (ErrorResponse, FieldError, PageMeta, PageLinks,
 * PagedResponse) and a reusable Idempotency-Key header parameter so individual controllers
 * never inline them. Every operation inherits the global bearerAuth security requirement;
 * public operations are explicitly allow-listed so an unregistered public endpoint causes a
 * contract-lint failure rather than shipping unauthenticated.
 */
@Configuration
public class OpenApiConfiguration {

    /**
     * Operation IDs that are intentionally public (no bearer token required).
     * Health check is served by Actuator and does not appear in the API doc.
     * Future login/refresh operations should be added here.
     */
    public static final Set<String> PUBLIC_OPERATION_IDS = Set.of();

    static final String SECURITY_SCHEME_NAME = "bearerAuth";
    static final String IDEMPOTENCY_KEY_PARAM = "IdempotencyKey";

    @Bean
    public OpenAPI openAPI(
            @Value("${app.api.public-base-url:http://localhost:8080}") String baseUrl,
            @Value("${spring.application.version:0.0.1-SNAPSHOT}") String version) {

        return new OpenAPI()
                .info(apiInfo(version))
                .addServersItem(new Server().url(baseUrl).description("Field Service API"))
                .components(components())
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    private Info apiInfo(String version) {
        return new Info()
                .title("Field Service API")
                .description("Platform API for field service operations management. " +
                        "All endpoints require a valid Bearer JWT unless explicitly allow-listed.")
                .version(version);
    }

    private Components components() {
        return new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, bearerScheme())
                .addParameters(IDEMPOTENCY_KEY_PARAM, idempotencyKeyParam())
                .addSchemas("FieldError", fieldErrorSchema())
                .addSchemas("ErrorResponse", errorResponseSchema())
                .addSchemas("PageMeta", pageMetaSchema())
                .addSchemas("PageLinks", pageLinksSchema())
                .addSchemas("PagedResponse", pagedResponseSchema());
    }

    private SecurityScheme bearerScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("RS256-signed JWT issued by the configured OIDC provider. " +
                        "Supply as Authorization: Bearer <token>.");
    }

    private Parameter idempotencyKeyParam() {
        return new Parameter()
                .name("Idempotency-Key")
                .in("header")
                .description("Client-generated idempotency key (16–128 characters, alphanumeric and hyphens). " +
                        "Required on all mutating operations (POST, PUT, PATCH, DELETE). " +
                        "Duplicate requests with the same key within the TTL return the cached response.")
                .required(true)
                .schema(new StringSchema().minLength(16).maxLength(128).pattern("^[a-zA-Z0-9\\-_]+$")
                        .example("req-2024-01-15-abc123def456"));
    }

    @SuppressWarnings("rawtypes")
    private Schema fieldErrorSchema() {
        return new ObjectSchema()
                .description("A single field-level validation violation.")
                .addProperty("field", new StringSchema()
                        .description("Request field path, e.g. 'priority' or 'body.description'")
                        .example("priority"))
                .addProperty("message", new StringSchema()
                        .description("Human-readable constraint violation message.")
                        .example("must not be blank"))
                .addRequiredItem("field")
                .addRequiredItem("message");
    }

    @SuppressWarnings("rawtypes")
    private Schema errorResponseSchema() {
        return new ObjectSchema()
                .description("Uniform error envelope returned for every non-2xx response.")
                .addProperty("code", new StringSchema()
                        .description("Stable error code string. Clients should branch on this value.")
                        .example("VALIDATION_FAILED"))
                .addProperty("message", new StringSchema()
                        .description("Human-readable summary. Never contains stack traces, SQL, or class names.")
                        .example("Request validation failed."))
                .addProperty("fieldErrors", new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/FieldError"))
                        .description("Per-field violations on 400 responses; empty list otherwise."))
                .addProperty("traceId", new StringSchema()
                        .description("MDC trace-id for log correlation.")
                        .example("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                .addRequiredItem("code")
                .addRequiredItem("message")
                .addRequiredItem("fieldErrors")
                .addRequiredItem("traceId");
    }

    @SuppressWarnings("rawtypes")
    private Schema pageMetaSchema() {
        return new ObjectSchema()
                .description("Pagination metadata embedded in every collection response.")
                .addProperty("number", new IntegerSchema()
                        .description("Zero-based page number. -1 in keyset mode.").example(0))
                .addProperty("size", new IntegerSchema()
                        .description("Requested page size.").example(20))
                .addProperty("totalElements", new IntegerSchema().format("int64")
                        .description("Total number of matching elements. -1 in keyset mode.").example(42L))
                .addProperty("totalPages", new IntegerSchema()
                        .description("Total pages at the requested size. -1 in keyset mode.").example(3))
                .addProperty("estimated", new BooleanSchema()
                        .description("True when counts are estimates (keyset mode); absent in offset mode."))
                .addRequiredItem("number")
                .addRequiredItem("size")
                .addRequiredItem("totalElements")
                .addRequiredItem("totalPages");
    }

    @SuppressWarnings("rawtypes")
    private Schema pageLinksSchema() {
        return new ObjectSchema()
                .description("Hypermedia navigation links embedded in collection responses.")
                .addProperty("next", new StringSchema()
                        .description("Link to the next page; null if this is the last page.")
                        .example("/api/v1/work-orders?page=1&size=20"))
                .addProperty("prev", new StringSchema()
                        .description("Link to the previous page; null on the first page.")
                        .example(null));
    }

    @SuppressWarnings("rawtypes")
    private Schema pagedResponseSchema() {
        return new ObjectSchema()
                .description("Generic paginated response envelope used by all collection endpoints.")
                .addProperty("data", new ArraySchema()
                        .description("Page of resource representations."))
                .addProperty("page", new Schema<>().$ref("#/components/schemas/PageMeta"))
                .addProperty("links", new Schema<>().$ref("#/components/schemas/PageLinks"))
                .addRequiredItem("data")
                .addRequiredItem("page")
                .addRequiredItem("links");
    }
}
