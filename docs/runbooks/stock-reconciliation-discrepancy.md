# Runbook: Stock Reconciliation Discrepancy

**Severity:** P1 — Any discrepancy between the stock ledger and balance tables indicates
a potential inventory integrity failure and must be triaged immediately.

**Alert:** `inventory_reconciliation_discrepancies_total` counter incremented > 0

---

## Overview

The reconciliation sweep runs on the `worker` deployable every 60 seconds under a
distributed advisory lock (exactly one replica sweeps per tick). It computes:

```
SUM(delta_quantity) GROUP BY (part_id, from_location_id) FROM stock_ledger
```

and compares this against `quantity_on_hand` in `stock_balance`. Any mismatch raises
a structured log alert and increments the Micrometer counter.

---

## 1. Identify the affected (part, location) pair

Search application logs for the alert pattern:

```
ALERT reconciliation_discrepancy part_id=<UUID> location_id=<UUID> ledger_sum=<N> balance=<M> diff=<D>
```

Note the `part_id` and `location_id` values.

## 2. Inspect the ledger tail

Query the most recent 20 ledger entries for the affected pair:

```sql
SELECT id, movement_type, delta_quantity, resulting_quantity,
       reason_code, work_order_id, actor_user_id, occurred_at
FROM stock_ledger
WHERE from_location_id = '<location_id>'
  AND part_id           = '<part_id>'
ORDER BY occurred_at DESC
LIMIT 20;
```

Look for:
- An unexpected delta (e.g., large positive ADJUSTMENT not reflected in the balance)
- A missing entry (the balance changed but no ledger row exists)
- A duplicate entry (same idempotency_key present twice)

## 3. Verify the balance

```sql
SELECT quantity_on_hand, quantity_reserved, updated_at, version
FROM stock_balance
WHERE part_id     = '<part_id>'
  AND location_id = '<location_id>';
```

## 4. Compute what the balance should be

```sql
SELECT SUM(delta_quantity) AS expected_balance
FROM stock_ledger
WHERE from_location_id = '<location_id>'
  AND part_id           = '<part_id>';
```

The difference between `expected_balance` and `quantity_on_hand` is the discrepancy.

## 5. Correction procedure (MANDATORY)

**DO NOT issue a direct UPDATE to `stock_balance`. This is strictly prohibited.**

All balance corrections must go through an audited `ADJUSTMENT` movement via the
stock movement service. This writes a new ledger entry and adjusts the balance
atomically, preserving the full audit trail.

```
POST /api/v1/inventory/adjust
{
  "partId": "<part_id>",
  "locationId": "<location_id>",
  "deltaQuantity": <difference>,
  "reasonCode": "RECONCILIATION_CORRECTION",
  "note": "Correcting discrepancy detected by reconciliation sweep on <date>"
}
```

This request requires `ADMIN` role and will create a new ledger entry with
`movement_type = ADJUSTMENT` and a correlation ID.

## 6. Post-correction verification

After the ADJUSTMENT is applied, wait for the next reconciliation sweep tick (~60 s)
and confirm the `inventory_reconciliation_discrepancies_total` counter does not increment
for this pair. Search for the alert log line — it should be absent.

---

## Data Classification

The `stock_ledger` table is classified **Internal** per BR-23.

- **Retention:** 24 months hot storage (primary database), then archived to cold storage.
- **Access:** Row-scoped to TECHNICIAN (own vehicle location only), all for DISPATCHER/ADMIN/MANAGER.
- **PII:** None. Only part IDs, location IDs, quantities, and actor UUIDs. Actor PII is in the `app_user` table.
- **Purge approach:** Expired rows are moved to an archive table via a scheduled ADJUSTMENT procedure;
  no DELETE is permitted on the primary table — only INSERT + SELECT.

---

## Invariant: No Module Writes Stock Except Inventory

Only `StockMovementServiceImpl` (inventory module) may write to `stock_balance` or `stock_ledger`.
No other module is permitted to issue balance changes directly. This is enforced by:

1. The `@Transactional(propagation = MANDATORY)` contract on `LedgerWriteService`.
2. The ArchUnit rule `StockLedgerAppendOnlyTest` banning delete paths at build time.
3. The database REVOKE on UPDATE/DELETE for the `fieldservice` runtime role.
4. Code review gate: PRs touching stock tables outside the inventory module are blocked.
