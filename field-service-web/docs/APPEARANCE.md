# Appearance Preference

## Resolution order

When deciding which CSS token set to apply, the system resolves in this order:

1. **localStorage mirror** (`fsvc_appearance`) — read synchronously before React mounts.
2. **API stored preference** — polled after auth; wins over mirror on first successful load and overwrites the mirror.
3. **OS media query** — used when the stored/mirrored value is `system`.
4. **Default** — `light` when no preference is stored or the stored value is unrecognisable.

## Authority rules

- The `<html data-appearance="…">` attribute is the single source of truth for CSS.  
  CSS must target `[data-appearance="dark"]`; no JavaScript stylesheet swapping occurs.
- The localStorage mirror holds the raw user-chosen setting (`light`, `dark`, or `system`), not the resolved value.
- The API stores the setting as an enum (`LIGHT`, `DARK`, `SYSTEM`). The web layer lowercases it before writing to the mirror.
- `system` is never written to the DOM attribute — it is always resolved to `light` or `dark` via `window.matchMedia`.

## No-flash mechanism

`main.jsx` runs `document.documentElement.setAttribute('data-appearance', resolveFromMirror())` synchronously, **before** `ReactDOM.createRoot()`. This is safe under the `default-src 'self'` CSP because it executes from a hashed ES module (`<script type="module" src="/src/main.jsx">`), not an inline script.

`index.html` seeds `data-appearance="light"` as the HTML default. Users without a stored preference see `light` with no flash.

## Persistence

Changes are persisted in two places simultaneously:

1. **localStorage** (`fsvc_appearance`) via `writeMirror()` — immediate, survives page reload without an API round-trip.
2. **API** (`PUT /api/v1/users/me/preferences`) — the `onPersist` callback on `AppearanceProvider` triggers a TanStack Query mutation so the preference roams across devices.

## Files

| File | Purpose |
|---|---|
| `src/appearance/appearanceMirror.js` | localStorage read/write with validation and SecurityError guard |
| `src/appearance/resolveAppearance.js` | Maps stored setting to `'light'` or `'dark'` |
| `src/appearance/AppearanceContext.js` | React context definition and `useAppearance` hook |
| `src/appearance/AppearanceProvider.jsx` | Provider: reads DOM, manages state, calls mirror and `onPersist` |
| `src/appearance/appearanceTelemetry.js` | `emitAppearanceChanged` — no PII, CustomEvent-based |
| `src/api/preferences.js` | `getPreferences` / `updatePreference` fetch wrappers |
