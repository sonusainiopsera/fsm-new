package com.fieldservice.api.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Attaches the platform's standard error responses to every operation so individual controllers
 * do not need to redeclare them.
 *
 * <p>Standard responses are only added if not already declared by the controller, so
 * controllers that override a specific status code (e.g. 409 with custom description) take
 * precedence over the platform default.
 *
 * <p>The 429 response carries an implied {@code Retry-After} header that clients must honour.
 */
@Component
public class StandardErrorResponsesCustomizer implements OpenApiCustomizer {

    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponse";
    private static final String MEDIA_TYPE = "application/json";

    private static final Map<String, String> STANDARD_ERROR_RESPONSES = Map.of(
            "400", "Bad Request — input validation failed; see fieldErrors for field-level detail.",
            "401", "Unauthorized — authentication required or the provided token is invalid or expired.",
            "403", "Forbidden — the caller does not have permission; also returned for out-of-scope resources (non-disclosure).",
            "404", "Not Found — the requested resource does not exist.",
            "409", "Conflict — the operation conflicts with the current resource state (illegal transition, duplicate key, or concurrent modification).",
            "422", "Unprocessable Entity — a business rule guard refused the operation.",
            "429", "Too Many Requests — rate limit exceeded; observe the Retry-After response header.",
            "503", "Service Unavailable — a required upstream service is temporarily down; retry with back-off."
    );

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) return;

        openApi.getPaths().forEach((path, pathItem) ->
                getOperations(pathItem).forEach(operation -> {
                    if (operation.getResponses() == null) {
                        operation.setResponses(new ApiResponses());
                    }
                    STANDARD_ERROR_RESPONSES.forEach((status, description) -> {
                        if (!operation.getResponses().containsKey(status)) {
                            operation.getResponses().addApiResponse(status, errorResponse(description));
                        }
                    });
                }));
    }

    private List<Operation> getOperations(PathItem pathItem) {
        return List.of(
                pathItem.getGet(), pathItem.getPost(), pathItem.getPut(),
                pathItem.getPatch(), pathItem.getDelete(), pathItem.getHead(), pathItem.getOptions()
        ).stream().filter(op -> op != null).toList();
    }

    @SuppressWarnings("rawtypes")
    private ApiResponse errorResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(MEDIA_TYPE,
                        new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA_REF))));
    }
}
