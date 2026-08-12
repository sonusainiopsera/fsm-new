# Privacy Administration Console

Three screens under `/admin/privacy/*`, accessible only to users holding the `PRIVACY_ADMIN` or `ADMIN` role. All other roles see the named `PermissionDeniedState` on direct URL visits; navigation entries are hidden in the shell for usability (server 403 is the real boundary).

## Screens

### Classification Registry (`/admin/privacy/classifications`)

Paginated table listing data classifications by module, entity name, field name, tier and handling notes. Sort is allow-listed to `entityName`, `fieldName`, `module`, `tier`. Page size is capped at 50.

The edit affordance opens a modal with a versioned PUT (`If-Match`-equivalent via version field in the request body). A 409 response renders a conflict notice and refetches the current row — the administrator can review the current value before re-submitting.

### Retention Schedule (`/admin/privacy/retention`)

Paginated table of retention policies. Each row shows the module, entity name, period (days), legal basis, ratification status, and enabled flag. **Unratified rows display an italic "Indicative placeholder" label in the Status column and are never presented as ratified targets.** A ratified row shows its ratified timestamp.

The **Dry Run** action calls `POST /api/v1/privacy/retention-policies/{id}/dry-run`. The result panel (rendered at `data-testid="dry-run-result"`) shows:
- Rows affected (tabular-numerals)
- Breakdown by entity type
- Estimated duration
- The explicit message **"No data will be changed."** — dry-run is read-only.

### DSAR Queue (`/admin/privacy/dsar`)

Paginated table of data subject access requests, sorted by due date ascending by default. Columns: type, subject type, subject ID, state, submitted date, due date with countdown. State-filter buttons are rendered with `aria-pressed` and drive server-side filtering.

**Countdown cell behaviour:**
- Remaining days = `⌈(dueAt - now) / 86400000⌉` computed in `computeRemainingDays(dueAt)` in `useDsarRequests.js`.
- **At-risk**: amber chip treatment applied **only** when the server supplies `atRisk: true` on the row. The client does not recompute the at-risk threshold independently.
- **Overdue**: rendered when remaining days < 0 — displays "⚠ Overdue (Nd)" in danger colour. Negative values are never shown as bare negative numbers.

Row click navigates to the detail page.

### DSAR Request Detail (`/admin/privacy/dsar/:id`)

Shows:
- Summary panel (id, type, subject, state, dates)
- Countdown banner (danger/amber/info based on overdue/at-risk/normal)
- Identity verification panel — if unverified, export and erasure actions include an explanation
- State history list (chronological, actor attributed)
- Export manifest table with per-section row counts

**Export download**: calls `GET /api/v1/privacy/dsar-requests/{id}/export` on each click via `useDsarExportUrl` mutation. The URL is never persisted in component state, local storage, or query cache. The mutation is `retry: false` and `gcTime: 0` so TanStack Query discards it immediately.

Export is hidden (with explanation) when:
- The request has not been identity-verified, or
- The request is in a terminal state (FULFILLED, REJECTED, WITHDRAWN)

**Erasure action**: shown only for `requestType === 'ERASURE'` and non-terminal states. Opens `ErasureConfirmDialog`.

## Erasure Confirmation Flow

`ErasureConfirmDialog` is a destructive confirmation:

1. The dialog states explicitly: this action is **irreversible**.
2. The dialog states: non-identifying transaction records are retained after erasure.
3. The **Confirm Erasure** button is disabled until the administrator types the exact phrase `CONFIRM_ERASURE` into the confirmation input.
4. Saturated danger colour (`var(--token-danger-*)`) is reserved for this dialog and the overdue countdown treatment — no other UI element in the admin console uses saturated danger styling.
5. The idempotency key (`Idempotency-Key` header) is auto-attached by `http.js buildHeaders` — double-click or network retry yields exactly one erasure.

## Countdown Computation

```js
// useDsarRequests.js
export function computeRemainingDays(dueAt) {
  const due = new Date(dueAt)
  const now = new Date()
  return Math.ceil((due.getTime() - now.getTime()) / (1000 * 60 * 60 * 24))
}
```

The result is a signed integer. Callers check `< 0` for overdue. At-risk treatment is driven exclusively by the server-supplied `atRisk` boolean field on each DSAR row — the client does not independently implement the threshold.

## Compensating Type-Safety Controls

The frontend uses JavaScript rather than TypeScript strict mode (open question Q6). The following compensating controls apply to all privacy admin code:

- **JSDoc**: every public function, component and hook has `@param` and `@returns` (or `@type`) annotations. Consumed by the CI `tsc --noEmit --checkJs` pass.
- **Strict ESLint**: the existing ESLint config (no-unused-vars, react/prop-types, etc.) runs in CI with zero new suppressions permitted.
- **Runtime shape validation**: unexpected API response shapes trigger the `ErrorState` via TanStack Query's `select` or error boundaries rather than crashing.
- **Prop discipline**: components receive only declared props; no spread-all patterns.

## Design Token Compliance

All visual values in the privacy admin components are sourced from `var(--token-*)` CSS custom properties. There are no hard-coded colours, spacings, radii or font sizes. The shared theme provides both light and dark mappings; switching appearance re-renders all components with no additional work at the screen level.
