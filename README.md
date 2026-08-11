# Field Service Operations Platform

A cloud-native field service management platform built with:

- **Java 21** with virtual threads
- **Spring Boot 3.3.5** — modular monolith
- **PostgreSQL 16** — primary data store
- **Spring Security** OAuth2 Resource Server with JWT

## Modules

| Module | Purpose |
|--------|---------|
| `platform` | Shared infrastructure: access-scope enforcement, error handling, persistence base |
| `domain` | Domain entities: work orders, assignments, sites, assets, inventory |
| `app` | Spring Boot application entry point, configuration, REST controllers |

## Building

```bash
./mvnw clean package -DskipTests
```

## Running Tests

```bash
./mvnw test
```

Tests require Docker (Testcontainers PostgreSQL 16).
