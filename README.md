# Field Service Operations Platform

A cloud-native field service operations platform built with Java 21, Spring Boot 3.5, and PostgreSQL.

## Architecture

Modular monolith with two deployable profiles from one artifact:
- **`api`** — HTTP REST API (dispatcher, technician, customer portal, ops manager)
- **`worker`** — Async event processor (SLA evaluator, notification fan-out, outbox poller)

### Module Structure

```
field-service-api/
├── platform/       # Shared security primitives, access scope, persistence abstractions
└── app/            # Spring Boot entry point, domain entities, REST API, async workers
```

## Access Control

Row-level scope enforcement via `AccessScopePredicateFactory` + `ScopedQueryExecutor`:

| Role | Scope |
|------|-------|
| DISPATCHER / ADMIN | Permit-all |
| MANAGER | Permit-all (read-oriented) |
| TECHNICIAN | Work orders where `assigned_technician_id` = their technician id |
| CUSTOMER | Work orders/sites where `site.customer_account_id` IN their linked accounts |

**Non-disclosure**: out-of-scope and nonexistent IDs both return the same 403 envelope.

## Running the API

```bash
# Requires: Java 21, PostgreSQL 16
export DB_URL=jdbc:postgresql://localhost:5432/fieldservice
export JWKS_URI=https://your-identity-provider/.well-known/jwks.json
java -jar app/target/app.jar --spring.profiles.active=api
```

## Running Tests

```bash
# Unit tests only (no Testcontainers)
mvn test -pl platform

# Integration tests (requires Docker for Testcontainers)
mvn test -pl app
```

## Technology Stack

- **Java 21** with virtual threads (`spring.threads.virtual.enabled=true`)
- **Spring Boot 3.5** — OAuth2 Resource Server, Method Security, Data JPA
- **PostgreSQL 16** — primary + read replica
- **Flyway** — schema migrations
- **Testcontainers** — integration tests with real PostgreSQL
