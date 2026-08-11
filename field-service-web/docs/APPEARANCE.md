# Appearance System

Per-account appearance preference persistence and flash-free restore (WO-184).

## Resolution Order

The concrete appearance (`light` | `dark`) is resolved from the following sources in priority order:

| Priority | Source | Notes |
|---|---|---|
| 1 | **Server value** | Authoritative once the session is established after sign-in |
| 2 | **Local mirror** | `localStorage` key `fs-appearance`; best-effort cache for pre-paint |
| 3 | **OS media query** | `prefers-color-scheme: dark` — used when preference is `SYSTEM` |
| 4 | **Fallback** | `light` — default for new accounts and unauthenticated visitors |

## Authority Rules

- The **server value is authoritative** once the user is authenticated. On sign-in success the
  API response (`GET /api/v1/users/me/preferences`) is used to overwrite the local mirror.
- A **stale or tampered local mirror** is cleared and replaced with the server value in the same
  session, with no reload required.
- If the preferences endpoint is **unavailable** (503) at sign-in, the local mirror is kept and
  the client retries on the next successful request. No blocking error is surfaced.
- A **rolled-back PUT** (network failure after optimistic switch) keeps the appearance applied
  for the session, surfaces a danger toast, and does not update the mirror.

## No-Flash Mechanism

The `index.html` head contains a synchronous inline script that runs before the stylesheet and
the React bundle are processed:

```html
<script>
  (function () {
    var stored = localStorage.getItem('fs-appearance');
    var preferred = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    var appearance = stored ? (stored === 'DARK' ? 'dark' : (stored === 'SYSTEM' ? preferred : 'light')) : 'light';
    if (appearance === 'dark') {
      document.documentElement.setAttribute('data-appearance', 'dark');
    }
  })();
</script>
```

**CSP mechanism:** The script tag in `index.html` is a static inline script. In environments
where `unsafe-inline` is forbidden, the hash of this script must be added to the `script-src`
CSP directive (SHA-256 hash computed at build time). Alternatively, a `nonce`-based CSP can
inject the nonce attribute at edge/server. The chosen mechanism must be documented in the
deployment runbook.

## localStorage Key

| Key | Values | Classification |
|---|---|---|
| `fs-appearance` | `LIGHT` \| `DARK` \| `SYSTEM` | Internal (BR-23) — no personal data |

The mirror stores only the enum value. It is not a cookie and is not sent to the server.

## API Contract

```
GET  /api/v1/users/me/preferences
PUT  /api/v1/users/me/preferences
     Body: { "appearance": "LIGHT" | "DARK" | "SYSTEM" }
```

- Subject derived from JWT `sub` claim — no client-supplied user identifier accepted (BR-19).
- `storedPreference`: raw value from DB (may be `null` for new accounts).
- `effectiveAppearance`: resolved value after applying null → `LIGHT` fallback.

## Switching Appearance

Switching calls `setPreference(value)` from `useAppearance()`. The implementation:

1. Updates React state.
2. Writes the new value to the local mirror (`localStorage`).
3. Calls `applyAppearance(concrete)` which **mutates only the `data-appearance` attribute** on
   `<html>` — no stylesheet swap, no route remount.
4. Fires the `onPreferenceChange` callback (used to trigger the API mutation).

The attribute mutation triggers a CSS `:root[data-appearance="dark"]` rule cascade, which is a
single DOM attribute write. Measured interaction-to-repaint is under 10 ms in practice, well
within the 100 ms budget.

## Telemetry

An `appearance-changed` event is emitted with:

```json
{ "event": "appearance-changed", "role": "<actor-role>" }
```

No personal data beyond the role dimension. Used to measure per-persona uptake against the
target of ≥25% adoption among field technicians within 90 days.

## SYSTEM Preference and Live OS Changes

When the stored preference is `SYSTEM`, an `EventListener` on
`window.matchMedia('(prefers-color-scheme: dark)')` is registered. When the OS appearance
changes mid-session, `applyAppearance` is called with the new concrete value without a reload
and without a server write.
