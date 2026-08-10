# Forge Implementation Log

| Field | Value |
|-------|-------|
| Project | 516cd243-9cc0-4ea9-b80e-a849650cdd2d |
| Branch | forge/ai-powered-field-service-manag-64043c14-run4-109wo |
| Started | 2026-08-10T23:17:02Z |

---

## WO-009: User Story: WO-009 - Mandatory AccessScope row-level query predicate enforcement
- **Status:** completed
- **Commit:** `5003fe9`
- **Files:** 48 (+2688/-1)
- **Duration:** 1615ss
- **Approach:** Built the complete Maven multi-module project scaffolding (platform, domain, app) and implemented the WO-009 access-scope security layer. The design uses a request-scoped AccessScopeContext that resolves JWT claims once per request, a registry-based AccessScopePredicateFactory that contributor beans populate at startup (with JPA-metamodel startup validation), and a ScopedQueryExecutor that ANDs the scope Specification into every read — including the count query so totalElements never leaks out-of-scope rows. Single-entity fetches use findOne(composedSpec) so absent and out-of-scope IDs throw the same ScopedAccessDeniedException, mapped to a uniform 403 with no existence disclosure. The UnscopedRead opt-out annotation with mandatory justification and a committed allowlist (enforced by a reflective test) gates the analytics read model exception surface.

## WO-002: User Story: WO-002 - Flyway baseline schema with UUIDv7 keys and integrity constraints
- **Status:** completed
- **Commit:** `8cf402e`
- **Files:** 34 (+1363/-103)
- **Duration:** 1392ss
- **Approach:** Replaced the minimal V1 migration with a comprehensive 4-migration Flyway baseline covering all 15 domain tables (V1: core schema with all integrity constraints; V2: sla_policy; V3: 6 performance indexes; V4: seed reference data). Added UuidV7 generator utility to the platform module and wired it into entity constructors (removing @GeneratedValue). Updated existing entities to match the new singular table names and field renames (sites→site, work_orders→work_order, etc.), added @Version to Assignment and StockBalance, and renamed Site.customerAccountId to customerId. Created all new domain entities (Customer, Technician, TechnicianCertification, Part, StockLocation, StockBalance, SlaPolicy) with corresponding repositories. Updated DomainScopeContributor to register all new entities. Fixed test infrastructure to use db/testfixtures (V1000 placeholder) instead of db/testdata to avoid Flyway version conflicts with V2 migration. Added SlaPolicyRepository to the @UnscopedRead allowlist.

## WO-006: User Story: WO-006 - Uniform error contract and strict request validation pipeline
- **Status:** completed
- **Commit:** `b77288d`
- **Files:** 29 (+1153/-59)
- **Duration:** 662ss
- **Approach:** Implemented the full WO-006 error contract on top of the minimal error handling from WO-009. Replaced ErrorEnvelope with ErrorResponse (record of code, message, fieldErrors, traceId) plus FieldError and a stable ErrorCode enum. Added 8 typed domain exceptions in the platform api package (ApiException hierarchy). Rewrote GlobalExceptionHandler to map all Spring/domain/security exceptions to the agreed status codes, read traceId from MDC, echo X-Trace-Id header, and log warn-for-4xx/error-for-5xx. Added TraceIdFilter that generates or propagates X-Trace-Id into MDC on every request. Added JacksonConfiguration hardening (FAIL_ON_UNKNOWN_PROPERTIES, FAIL_ON_NULL_FOR_PRIMITIVES, no case-insensitive enums). Created @AllowedValues and @SafeText validation annotations. Updated SecurityConfiguration to use JSON entry points for filter-stage 401/403. Added UniformErrorController for filter-stage errors before the advice is reachable. Configured server.error to never expose stacktraces or messages. Added MockMvc test matrix covering all status codes, headers, field-errors, internals-leak scan, and zero-rows-written validation assertion.
