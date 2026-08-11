package com.fieldservice.app.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attaches platform-standard responses and security requirements to every operation so
 * individual controllers never repeat them.
 *
 * <p>Standard responses added to every operation:
 * 400 (Bad Request), 401 (Unauthorized), 403 (Forbidden), 404 (Not Found),
 * 409 (Conflict), 422 (Unprocessable Entity), 429 (Too Many Requests),
 * 503 (Service Unavailable). All reference the shared {@code ErrorResponse} schema.
 *
 * <p>Mutating operations (POST, PUT, PATCH, DELETE) additionally receive the
 * {@code Idempotency-Key} header parameter reference.
 *
 * <p>Public operations in the allow-list have their security requirement cleared so the
 * contract accurately reflects which operations require authentication.
 */
@Component
public class StandardResponsesCustomizer implements OpenApiCustomizer {

    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponse";
    private static final String IDEMPOTENCY_PARAM_REF =
            "#/components/parameters/" + OpenApiConfiguration.IDEMPOTENCY_KEY_PARAM;

    private static final Set<PathItem.HttpMethod> MUTATING_METHODS = Set.of(
            PathItem.HttpMethod.POST,
            PathItem.HttpMethod.PUT,
            PathItem.HttpMethod.PATCH,
            PathItem.HttpMethod.DELETE
    );

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }

        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((method, operation) -> {
                    applyStandardResponses(operation);
                    if (MUTATING_METHODS.contains(method)) {
                        applyIdempotencyKey(operation);
                    }
                    applySecurityRequirement(operation);
                })
        );
    }

    private void applyStandardResponses(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponses responses = operation.getResponses();

        addIfAbsent(responses, "400", "Bad Request — the request failed validation.",
                Map.of("Content-Type", "application/json"));
        addIfAbsent(responses, "401", "Unauthorized — a valid Bearer JWT is required.");
        addIfAbsent(responses, "403", "Forbidden — the caller lacks the required role or the resource is not in scope.");
        addIfAbsent(responses, "404", "Not Found — the resource does not exist.");
        addIfAbsent(responses, "409", "Conflict — a duplicate idempotency key or state conflict.");
        addIfAbsent(responses, "422", "Unprocessable Entity — the request is structurally valid but semantically rejected.");
        addIfAbsent(responses, "429", "Too Many Requests — rate limit exceeded. Retry after the Retry-After header value.");
        addIfAbsent(responses, "503", "Service Unavailable — a downstream dependency is unavailable.");
    }

    private void addIfAbsent(ApiResponses responses, String status, String description) {
        addIfAbsent(responses, status, description, null);
    }

    @SuppressWarnings("rawtypes")
    private void addIfAbsent(ApiResponses responses, String status, String description,
                              Map<String, String> extraHeaders) {
        if (responses.containsKey(status)) {
            return;
        }
        Schema errorRef = new Schema<>().$ref(ERROR_SCHEMA_REF);
        MediaType mediaType = new MediaType().schema(errorRef);
        Content content = new Content()
                .addMediaType("application/json", mediaType);

        ApiResponse response = new ApiResponse()
                .description(description)
                .content(content);

        if ("429".equals(status)) {
            response.addHeaderObject("Retry-After",
                    new io.swagger.v3.oas.models.headers.Header()
                            .description("Number of seconds to wait before retrying.")
                            .schema(new io.swagger.v3.oas.models.media.IntegerSchema()));
        }

        responses.addApiResponse(status, response);
    }

    private void applyIdempotencyKey(Operation operation) {
        List<Parameter> params = operation.getParameters();
        if (params != null && params.stream().anyMatch(
                p -> OpenApiConfiguration.IDEMPOTENCY_KEY_PARAM.equals(p.getName()))) {
            return;
        }
        Parameter ref = new Parameter().$ref(IDEMPOTENCY_PARAM_REF);
        operation.addParametersItem(ref);
    }

    private void applySecurityRequirement(Operation operation) {
        String operationId = operation.getOperationId();
        if (operationId != null && OpenApiConfiguration.PUBLIC_OPERATION_IDS.contains(operationId)) {
            operation.setSecurity(List.of());
            return;
        }
        if (operation.getSecurity() == null) {
            operation.addSecurityItem(
                    new SecurityRequirement().addList(OpenApiConfiguration.SECURITY_SCHEME_NAME));
        }
    }
}
