/**
 * Persona density presets for the four role-specific surfaces.
 *
 * Each preset supplies token-level overrides that drive row height,
 * control height, touch-target minimum, card padding, base text size,
 * and column-count breakpoints.  Components reference --density-* custom
 * properties injected by PersonaDensityProvider; no visual literal may
 * appear in a CSS module or JSX file outside this definition file.
 *
 * Implementation order: TECHNICIAN is defined first to honour the
 * field-first rule in BR-35.  All other presets are verified against
 * the technician baseline in tests so the field surface is never regressed.
 */

/**
 * @typedef {Object} PersonaDensityPreset
 * @property {'technician'|'dispatcher'|'manager'|'customer'} persona
 * @property {'comfortable'|'compact'} density
 * @property {number} rowHeight         - Minimum table row height in px
 * @property {number} controlHeight     - Form control / button height in px
 * @property {number} touchTargetMin    - Minimum interactive hit-target size in px
 * @property {string} cardPaddingToken  - CSS token name for card padding step
 * @property {string} baseTextSizeToken - CSS token name for base body text size
 * @property {string} fontStackToken    - CSS token name for font family
 * @property {{ 360: number, 768: number, 1440: number }} columnCount
 * @property {'bottom'|'top-bar'|'drawer'|'inline'} primaryActionPlacement
 * @property {number} bodyContrastMin   - Minimum WCAG contrast ratio for body text
 */

// ── 1. Technician (field-first, BR-35) ───────────────────────────────────────
// Minimum 44px touch targets, single column at every breakpoint,
// bottom-anchored primary action in thumb reach, system font stack for
// fastest first paint, raised 7:1 body contrast in light (vs 4.5:1 baseline).
export const TECHNICIAN_PRESET = {
  persona: 'technician',
  density: 'comfortable',
  rowHeight: 56,
  controlHeight: 48,
  touchTargetMin: 44,
  cardPaddingToken: '--space-6',
  baseTextSizeToken: '--fs-base',
  fontStackToken: '--font-sans',
  columnCount: { 360: 1, 768: 1, 1440: 1 },
  primaryActionPlacement: 'bottom',
  bodyContrastMin: 7.0,
};

// ── 2. Dispatcher ─────────────────────────────────────────────────────────────
// Highest density: 32px compact table rows, drawer-based detail panel,
// keyboard-first affordances.
export const DISPATCHER_PRESET = {
  persona: 'dispatcher',
  density: 'compact',
  rowHeight: 32,
  controlHeight: 32,
  touchTargetMin: 32,
  cardPaddingToken: '--space-3',
  baseTextSizeToken: '--fs-sm',
  fontStackToken: '--font-sans',
  columnCount: { 360: 1, 768: 2, 1440: 4 },
  primaryActionPlacement: 'drawer',
  bodyContrastMin: 4.5,
};

// ── 3. Operations Manager ─────────────────────────────────────────────────────
// Chart-forward with generous KPI cards and low text density.
export const MANAGER_PRESET = {
  persona: 'manager',
  density: 'comfortable',
  rowHeight: 56,
  controlHeight: 40,
  touchTargetMin: 40,
  cardPaddingToken: '--space-8',
  baseTextSizeToken: '--fs-base',
  fontStackToken: '--font-sans',
  columnCount: { 360: 1, 768: 2, 1440: 3 },
  primaryActionPlacement: 'top-bar',
  bodyContrastMin: 4.5,
};

// ── 4. Customer ───────────────────────────────────────────────────────────────
// Most spacious variant, plain language, no internal terminology or codes.
export const CUSTOMER_PRESET = {
  persona: 'customer',
  density: 'comfortable',
  rowHeight: 72,
  controlHeight: 48,
  touchTargetMin: 44,
  cardPaddingToken: '--space-8',
  baseTextSizeToken: '--fs-base',
  fontStackToken: '--font-sans',
  columnCount: { 360: 1, 768: 1, 1440: 2 },
  primaryActionPlacement: 'bottom',
  bodyContrastMin: 4.5,
};

/** All persona presets keyed by name, in field-first order (BR-35). */
export const PERSONA_PRESETS = {
  technician: TECHNICIAN_PRESET,
  dispatcher: DISPATCHER_PRESET,
  manager: MANAGER_PRESET,
  customer: CUSTOMER_PRESET,
};

/** @type {ReadonlyArray<string>} */
export const PERSONA_NAMES = Object.keys(PERSONA_PRESETS);

/**
 * Returns the density preset for the given persona name, or null for unknown.
 * @param {string} personaName
 * @returns {PersonaDensityPreset|null}
 */
export function getPersonaPreset(personaName) {
  return PERSONA_PRESETS[personaName] ?? null;
}

/**
 * Maps a JWT roles array to the most appropriate persona preset.
 * Role priority: TECHNICIAN > DISPATCHER > MANAGER > CUSTOMER.
 * Returns null for ADMIN (no fixed persona — uses comfortable default).
 *
 * @param {string[]} roles
 * @returns {PersonaDensityPreset|null}
 */
export function resolvePersonaPreset(roles) {
  if (!Array.isArray(roles) || roles.length === 0) return null;
  if (roles.includes('TECHNICIAN')) return TECHNICIAN_PRESET;
  if (roles.includes('DISPATCHER')) return DISPATCHER_PRESET;
  if (roles.includes('MANAGER')) return MANAGER_PRESET;
  if (roles.includes('CUSTOMER')) return CUSTOMER_PRESET;
  return null;
}

/**
 * Resolves column count for the given preset at a specific viewport width.
 * Returns the column count for the largest breakpoint that is ≤ the viewport.
 *
 * @param {PersonaDensityPreset} preset
 * @param {number} viewportWidth - Viewport width in px
 * @returns {number}
 */
export function resolveColumnCount(preset, viewportWidth) {
  const breakpoints = [360, 768, 1440];
  let count = preset.columnCount[360];
  for (const bp of breakpoints) {
    if (viewportWidth >= bp) count = preset.columnCount[bp];
  }
  return count;
}
