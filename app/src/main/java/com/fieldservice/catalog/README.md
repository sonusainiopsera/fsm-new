# Catalog Module

Reference data for the customer → site → asset hierarchy. Downstream modules (work orders, inventory) depend on this module only through the `CatalogQueryPort` interface.

## Package structure

```
catalog/
  api/              Public interface & projection types (CatalogQueryPort, CustomerRef, SiteRef, AssetRef)
  application/      CatalogService — CRUD, hierarchy guards, deactivation, outbox events
  web/              REST controllers and request DTOs
    dto/            Inbound request bodies (Bean Validation, no unknown properties)
```

## REST endpoints

| Method | Path                                        | Roles                          |
|--------|---------------------------------------------|--------------------------------|
| GET    | /api/v1/customers                           | DISPATCHER, ADMIN, MANAGER     |
| GET    | /api/v1/customers/{id}                      | DISPATCHER, ADMIN, MANAGER     |
| POST   | /api/v1/customers                           | DISPATCHER, ADMIN, MANAGER     |
| PUT    | /api/v1/customers/{id}                      | DISPATCHER, ADMIN, MANAGER     |
| DELETE | /api/v1/customers/{id}                      | DISPATCHER, ADMIN, MANAGER     |
| GET    | /api/v1/customers/{customerId}/sites        | DISPATCHER, ADMIN, MANAGER, CUSTOMER |
| GET    | /api/v1/sites/{id}                          | DISPATCHER, ADMIN, MANAGER, CUSTOMER |
| POST   | /api/v1/customers/{customerId}/sites        | DISPATCHER, ADMIN, MANAGER     |
| DELETE | /api/v1/sites/{id}                          | DISPATCHER, ADMIN, MANAGER     |
| GET    | /api/v1/sites/{siteId}/assets               | All authenticated roles        |
| GET    | /api/v1/assets/{id}                         | All authenticated roles        |
| POST   | /api/v1/sites/{siteId}/assets               | DISPATCHER, ADMIN, MANAGER     |
| DELETE | /api/v1/assets/{id}                         | DISPATCHER, ADMIN, MANAGER     |

DELETE is a logical deactivation (sets `active=false`, `deactivated_at`). Physical deletion is reserved for the GDPR erasure path.

## Hierarchy invariants

Creating a child under an inactive parent is refused with HTTP 422 (`BusinessGuardException("INACTIVE_PARENT", ...)`):
- Site under inactive customer → 422
- Asset under inactive site → 422

Deactivating a customer cascade-deactivates all its active sites and their active assets in the same transaction.

## Row scoping

All reads are routed through `ScopedQueryExecutor`. The `AccessScope` is built from JWT claims:
- **CUSTOMER** — sees only customers/sites/assets in their `customerAccountIds` claim
- **TECHNICIAN** — permit-all for reads; denied on management endpoints
- **DISPATCHER / ADMIN / MANAGER** — permit-all

Out-of-scope records return 404 (non-disclosure: existence is not revealed).

## Outbox events

| Event type              | Published on    | Key field     |
|-------------------------|-----------------|---------------|
| catalog.CustomerChanged | Create / Update / Deactivate | accountCode |
| catalog.SiteChanged     | Create / Deactivate          | siteCode    |
| catalog.AssetChanged    | Create / Deactivate          | assetTag (FTF KPI join key) |

Events are published atomically with the domain row via `DomainEventPublisher` (`MANDATORY` propagation).

## Data classification and retention register

| Field group                              | Classification | Retention rule |
|------------------------------------------|----------------|----------------|
| Customer contact (primaryContactEmail etc.) | Confidential | Purge 12 months after `relationship_ended_on`; GDPR erasure path for early removal |
| Customer legal/account fields            | Internal       | Retain 7 years after relationship end (regulatory) |
| Site address, postcode                   | Internal       | Retain while customer active; purge on GDPR request |
| Asset fields (assetTag, model, etc.)     | Internal       | Retain for asset lifetime; no personal data |

Contact PII (`primaryContactName`, `primaryContactEmail`, `primaryContactPhone`) must never appear in log output or outbox event payloads. The `PiiRedactionUtility.toPayloadMap(payload)` call in `CatalogService` ensures only non-PII fields reach the outbox.

## Audit trail

Customer and Asset entities carry `@Audited` (Hibernate Envers). Site was already audited. Audit tables (`customer_aud`, `asset_aud`) are created by the V22 Flyway migration. The `do_not_audit_optimistic_locking_field=true` property in `application.yml` prevents the version counter from appearing in audit records.

## Sort injection prevention

Each entity has a `SortAllowList` (CUSTOMER_SORTS, SITE_SORTS, ASSET_SORTS) in `CatalogService`. Unknown sort field names raise `InvalidSortException` (HTTP 400) before any query is constructed.

## Pagination

`PageQuery.MAX_SIZE = 50` is enforced via the compact constructor's clamping. Clients supplying `size > 50` receive a page of at most 50 records.

## Migration compatibility

V22 is expand-only (additive DDL only). New columns are nullable or have defaults. The Flyway migration runs ahead of the new application version in a rolling deploy without breaking the prior version.
