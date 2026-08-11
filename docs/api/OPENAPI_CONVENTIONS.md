# OpenAPI Conventions

The Field Service API publishes a single OpenAPI 3 contract from `/api-docs`.  
Every cross-cutting platform convention (error envelope, pagination envelope, Idempotency-Key, bearer auth) is defined once in shared components and referenced by every operation — no controller inlines them.

---

## Contract endpoint

| Profile | `/api-docs` JSON | `/swagger-ui` |
|---------|-----------------|---------------|
| `dev`   | ✅ enabled      | ✅ enabled    |
| default / production | ✅ enabled | ❌ disabled |

The raw JSON document is always available for tooling (client generation, lint). The interactive UI is restricted to the `dev` profile.

---

## Adding a new endpoint

1. Annotate the controller method with `@Operation(operationId = "uniqueCamelCaseId")`.
2. Annotate success response with `@ApiResponse(responseCode = "200", description = "...")` — only describe the **2xx** case; the platform customiser adds the standard error responses automatically.
3. For collection (paginated) endpoints:
   - Return `ResponseEntity<PagedResponse<YourResponse>>`.
   - Declare `@RequestParam(required = false) Integer page`, `size`, `sort` parameters.
   - Annotate the method with `@Parameter(name = "page", ...)` etc. if you want custom descriptions.
4. For mutating operations (POST / PUT / PATCH / DELETE):
   - The `StandardResponsesCustomizer` automatically injects the `Idempotency-Key` header reference and a `409` response — you do not need to declare them.
5. Run the lint test to verify conventions hold: `./mvnw test -pl app -Dtest=ContractLintIT`.

---

## Shared components

| Component ref | Description |
|---------------|-------------|
| `#/components/schemas/ErrorResponse` | Uniform error envelope (code, message, fieldErrors, traceId) |
| `#/components/schemas/FieldError` | Per-field validation violation (field, message) |
| `#/components/schemas/PageMeta` | Pagination metadata (number, size, totalElements, totalPages, estimated) |
| `#/components/schemas/PageLinks` | Navigation links (next, prev) |
| `#/components/schemas/PagedResponse` | Collection envelope (data, page, links) |
| `#/components/parameters/IdempotencyKey` | Idempotency-Key header — injected automatically on mutating ops |
| `#/components/securitySchemes/bearerAuth` | RS256 Bearer JWT scheme — required on all non-public operations |

---

## Lint rules

`ContractLintIT` enforces these rules across every operation at build time:

| Rule | Description |
|------|-------------|
| `security-required` | Every operation has `bearerAuth` in its security requirement unless explicitly allow-listed in `OpenApiConfiguration.PUBLIC_OPERATION_IDS`. |
| `error-response-required` | Every operation declares at least one 4xx response (`400`, `401`, `403`, `404`, `409`, `422`, `429`, `503`). |
| `pagination-params` | Every collection GET operation (paged envelope response) declares `page`, `size`, and `sort` query parameters. |
| `idempotency-key-required` | Every mutating operation (POST/PUT/PATCH/DELETE) declares the `Idempotency-Key` header parameter. |
| `conflict-response-required` | Every mutating operation declares a `409 Conflict` response. |

A lint violation includes the offending `operationId`, HTTP method, and rule name so the fix is obvious.

---

## Snapshot management

The committed `app/src/test/resources/openapi-snapshot.json` is the canonical normalised contract.  
`SnapshotIT` compares the live generated document against it on every build after applying normalisation (volatile field stripping + deterministic key ordering).

**Volatile fields stripped before comparison:**
- `info.version` — replaced with `{{version}}`
- `servers[*].url` — replaced with `{{base-url}}`
- `x-generated-on` — removed

**To regenerate after an intentional API change:**

```bash
./mvnw test -pl app -Dtest=SnapshotIT -Dupdate-snapshot=true
git add app/src/test/resources/openapi-snapshot.json
git commit -m "Update OpenAPI snapshot for <change description>"
```

Snapshot mismatch fails the build with a human-readable line-level diff and the regeneration command, so the fix is always one command away.

---

## Public operation allow-list

Operations accessible without a Bearer token must be explicitly registered in  
`OpenApiConfiguration.PUBLIC_OPERATION_IDS`. Any newly added public endpoint that is **not** in this set will fail the `security-required` lint rule.

Current allow-listed operations: *(none — all endpoints require authentication)*
