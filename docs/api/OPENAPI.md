# OpenAPI Contract

The Field Service Platform API publishes an OpenAPI 3 contract served at `/api-docs` under the `api` Spring profile.

## Endpoints

| Path | Notes |
|------|-------|
| `GET /api-docs` | Machine-readable OpenAPI 3.0.1 JSON. Always available under the `api` profile. |
| `GET /swagger-ui.html` | Interactive UI. **Disabled in production** (`SPRINGDOC_SWAGGER_UI_ENABLED=false` by default). Enable by setting `SPRINGDOC_SWAGGER_UI_ENABLED=true` in non-production deployments. |

## Platform Conventions

Every endpoint in this API must conform to the following rules, enforced by `OpenApiContractLintTest`:

### Authentication
All operations carry the `bearerAuth` global security requirement unless explicitly allow-listed in `OpenApiConfiguration.PUBLIC_OPERATION_IDS` (currently: `login`, `refresh`, `logout`, `streamTicket`, `getHealth`, `getInfo`). A newly added public endpoint that omits its entry in the allow-list will fail the lint test.

### Standard Error Responses
Every operation automatically receives the following responses (added by `StandardErrorResponsesCustomizer`):

| Status | Meaning |
|--------|---------|
| `400` | Input validation failed; see `fieldErrors` |
| `401` | Authentication required or token invalid |
| `403` | Forbidden / resource not found (non-disclosure) |
| `404` | Resource not found (non-scoped entities only) |
| `409` | Illegal state transition or concurrent modification |
| `422` | Business rule guard refused the operation |
| `429` | Rate limit exceeded — observe `Retry-After` |
| `503` | Upstream service temporarily unavailable |

All error responses use the `ErrorResponse` shared component schema.

### Mutating Operations (POST, PUT, PATCH, DELETE)
All mutating operations must:
- Declare a `409` conflict response
- Accept an `Idempotency-Key` request header (`$ref: '#/components/parameters/IdempotencyKey'`)

### Collection Operations
Collection endpoints returning a paginated list must:
- Use the `PagedResponse` envelope (`data`, `page`, `links` fields)
- Accept `page`, `size`, and `sort` query parameters

## Adding a New Endpoint

1. Create a `@RestController` with `@RequestMapping` under `/api/v1/`.
2. Standard error responses (400, 401, 403, 404, 409, 422, 429, 503) are attached automatically — do not redeclare them unless you need a custom description.
3. If the endpoint is a mutating operation, reference `$ref: '#/components/parameters/IdempotencyKey'` in its parameters.
4. If the endpoint is a collection, return `PagedResponse<T>` and accept `page`/`size`/`sort` parameters.
5. If the endpoint is public (no auth), add its `operationId` to `OpenApiConfiguration.PUBLIC_OPERATION_IDS`.
6. Run `mvn test -pl app -Dtest=OpenApiContractLintTest` to verify zero violations.
7. Regenerate the snapshot (see below) and commit it.

## Regenerating the Snapshot

The committed snapshot at `app/src/test/resources/openapi/snapshot.json` locks the contract. Any undeclared change fails `OpenApiSnapshotTest`.

To update after an intentional contract change:

```bash
mvn test -pl app -Dtest=OpenApiSnapshotTest -DUPDATE_SNAPSHOT=true
```

Then commit the updated `app/src/test/resources/openapi/snapshot.json`.

**First-time bootstrap:** If the snapshot file contains `{}` (the initial placeholder), the test auto-generates it on the first run and passes. Commit the generated file and re-run to verify stability.

## Build Artifact

Every test run writes the raw OpenAPI spec to:

```
app/target/openapi/field-service-api.json
```

This artifact is consumed by the frontend typed client generation story. It is produced without booting a production server — the test profile with MockMvc generates it.

## Data Classification

Examples and descriptions in the spec must contain **placeholder values only**:
- No real customer names, email addresses, or phone numbers
- No bearer tokens or password values
- No internal hostnames (`.internal`, `.corp.`, database connection strings)

The `documentContainsNoForbiddenSubstrings` lint test enforces this.
