# Testing Guide

## Seed Data

Anonymised test fixtures live in `src/test/resources/fixtures/seed-core.sql`.

### Users

| UUID                                   | Email                    | Role        |
|----------------------------------------|--------------------------|-------------|
| `a0000000-0000-0000-0000-000000000001` | admin@example.test       | ADMIN       |
| `a0000000-0000-0000-0000-000000000002` | manager@example.test     | MANAGER     |
| `a0000000-0000-0000-0000-000000000003` | dispatcher@example.test  | DISPATCHER  |
| `a0000000-0000-0000-0000-000000000004` | tech@example.test        | TECHNICIAN  |
| `a0000000-0000-0000-0000-000000000005` | customer@example.test    | CUSTOMER    |

### Notification Preferences in Seed

| User        | Category              | Channel | Enabled |
|-------------|-----------------------|---------|---------|
| TECHNICIAN  | WORK_ORDER_ASSIGNED   | EMAIL   | false   |
| TECHNICIAN  | WORK_ORDER_ASSIGNED   | IN_APP  | true    |
| CUSTOMER    | SLA_BREACH            | EMAIL   | false   |
| CUSTOMER    | SLA_BREACH            | SMS     | false   |
| CUSTOMER    | SLA_BREACH            | IN_APP  | false   |
| CUSTOMER    | SLA_BREACH            | PUSH    | false   |

ADMIN, MANAGER, and DISPATCHER have **no explicit preferences** — `resolveEffective` will return all channels for these users (default-on).

## Running the Tests

### Unit Tests (no infrastructure required)

```bash
cd field-service-api
./mvnw test -Dtest=NotificationPreferenceServiceTest
```

### Controller Slice Tests (no infrastructure required)

The controller tests use `@WebMvcTest` with a mocked `JwtDecoder` and a mocked service bean.
No database or real JWT issuer is needed.

```bash
./mvnw test -Dtest=NotificationPreferenceControllerTest
```

### All Tests

```bash
./mvnw verify
```

Integration tests (if added) that use `@SpringBootTest` require Docker for Testcontainers
to spin up a PostgreSQL container automatically.

## Key Test Scenarios

### `resolveEffective` (NotificationPreferenceServiceTest)

| Scenario                                | Expected Result               |
|-----------------------------------------|-------------------------------|
| No rows in DB for user+category         | All 4 channels returned       |
| All channels stored, some enabled       | Only enabled channels         |
| Partial rows (2 of 4 channels stored)   | Only stored + enabled         |
| All channels explicitly disabled        | Empty set                     |
| Repository throws exception             | All channels (fail-safe)      |

### Access Control (NotificationPreferenceControllerTest)

| Caller     | Target User    | Expected HTTP |
|------------|----------------|---------------|
| Self       | Own userId     | 200           |
| ADMIN      | Any userId     | 200           |
| Non-admin  | Other userId   | 403           |
| No token   | Any userId     | 401           |

### Validation (NotificationPreferenceControllerTest)

| Scenario                                | Expected HTTP | Field in error         |
|-----------------------------------------|---------------|------------------------|
| Empty `preferences` list                | 400           | `preferences`          |
| `null` category in item                 | 400           | `preferences[0].category` |
| `null` channel in item                  | 400           | `preferences[0].channel`  |
| Missing `preferences` key entirely      | 400           | `preferences`          |

## JWT Token Format

The JWT resource server expects tokens with:

```json
{
  "sub": "<user-uuid>",
  "roles": ["ADMIN"],
  "iss": "<configured-issuer-uri>"
}
```

The `roles` claim drives `hasRole('ADMIN')` checks. Users without the `ADMIN` role
in their token may only access their own notification preferences.

## Environment Variables

| Variable            | Default                                 | Description                    |
|---------------------|-----------------------------------------|--------------------------------|
| `DATASOURCE_URL`    | `jdbc:postgresql://localhost:5432/fieldservice` | PostgreSQL JDBC URL   |
| `DATASOURCE_USERNAME` | `fieldservice`                        | Database username              |
| `DATASOURCE_PASSWORD` | `fieldservice`                        | Database password              |
| `JWT_ISSUER_URI`    | `http://localhost:8080`                 | OIDC issuer for JWT validation |
| `SERVER_PORT`       | `8080`                                  | HTTP listen port               |
