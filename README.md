# field-service-api

Cloud-native field service operations platform API — Spring Boot 3 / Java 21 / PostgreSQL.

## Architecture

Multi-module Maven project with two deployables from one artifact:

| Module | Purpose |
|--------|---------|
| `platform` | Shared security primitives: `AccessScope`, `ScopedRepository`, `ScopedQueryExecutor`, row-level predicate framework |
| `app` | Spring Boot application entry point, domain modules (work order, site, customer), REST controllers, security configuration, Flyway migrations |

## Running locally

```bash
# Start PostgreSQL (requires Docker)
docker compose up -d postgres

# Run the API
./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=api
```

## Running tests

```bash
# Unit and component tests (no Docker required, uses H2)
./mvnw test

# Integration tests (requires Docker for Testcontainers PostgreSQL)
./mvnw verify -Pintegration
```

## Access Control

Every read against a scoped entity carries a mandatory row-scope predicate derived from the
authenticated JWT. The scope is resolved once per request by `AccessScopeResolver` and
composed into all queries by `ScopedQueryExecutor`. Out-of-scope and nonexistent resources
both return HTTP 403 — the two cases are deliberately indistinguishable (non-disclosure).

Role → row scope mapping:

| Role | Work Order Scope |
|------|-----------------|
| DISPATCHER | All work orders |
| ADMIN | All work orders |
| MANAGER | All work orders (read-only) |
| TECHNICIAN | Only work orders where `assigned_technician_id = JWT.technician_id` |
| CUSTOMER | Only work orders where `site.customer_account_id IN JWT.customer_account_ids` |
