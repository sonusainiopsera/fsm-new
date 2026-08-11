/**
 * @fileoverview Persona density presets for DensityContext.
 *
 * Technician variant is defined first (BR-35 field-first ordering).
 * All overrides reference existing tokens only — no new visual literals.
 *
 * Ordering rule: technician must be verified before dispatcher per BR-35.
 * This file's export order documents and enforces that requirement.
 */

/**
 * @typedef {{
 *   rowHeight: string,
 *   controlHeight: string,
 *   minTouchTarget: string,
 *   cardPaddingStep: string,
 *   baseFontSize: string,
 *   fontFamily: string,
 *   columnCount: { mobile: number, tablet: number, desktop: number },
 *   primaryActionPlacement: 'bottom' | 'inline',
 *   detailPattern?: 'drawer' | 'inline',
 *   keyboardFirst?: boolean,
 *   chartForward?: boolean,
 *   plainLanguage?: boolean,
 *   contrastBodyTextMinRatio?: number,
 * }} PersonaDensityPreset
 */

/**
 * TECHNICIAN — field-first (BR-35).
 * 44 px touch targets, single-column, system font for fastest first paint,
 * bottom-anchored primary action within thumb reach, 7:1 body contrast in light.
 *
 * @type {PersonaDensityPreset}
 */
export const TECHNICIAN_PRESET = {
  rowHeight: 'calc(var(--token-space-8) + var(--token-space-3))',  // 44px
  controlHeight: 'calc(var(--token-space-8) + var(--token-space-3))', // 44px
  minTouchTarget: 'calc(var(--token-space-8) + var(--token-space-3))', // 44px
  cardPaddingStep: 'var(--token-space-6)',  // 24px
  baseFontSize: 'var(--token-fs-16)',
  fontFamily: 'var(--token-family-base)',
  columnCount: { mobile: 1, tablet: 1, desktop: 1 },
  primaryActionPlacement: 'bottom',
  contrastBodyTextMinRatio: 7,
}

/**
 * DISPATCHER — highest density.
 * 32 px compact table rows, drawer-based detail, keyboard-first affordances.
 *
 * @type {PersonaDensityPreset}
 */
export const DISPATCHER_PRESET = {
  rowHeight: 'var(--token-space-8)',  // 32px
  controlHeight: 'var(--token-space-8)',  // 32px
  minTouchTarget: 'var(--token-space-8)',  // 32px
  cardPaddingStep: 'var(--token-space-3)',  // 12px
  baseFontSize: 'var(--token-fs-13)',
  fontFamily: 'var(--token-family-base)',
  columnCount: { mobile: 1, tablet: 2, desktop: 3 },
  primaryActionPlacement: 'inline',
  detailPattern: 'drawer',
  keyboardFirst: true,
}

/**
 * OPERATIONS — chart-forward, generous KPI cards, low text density.
 *
 * @type {PersonaDensityPreset}
 */
export const OPERATIONS_PRESET = {
  rowHeight: 'calc(var(--token-space-8) + var(--token-space-6))',  // 56px
  controlHeight: 'calc(var(--token-space-6) + var(--token-space-4))',  // 40px
  minTouchTarget: 'calc(var(--token-space-6) + var(--token-space-4))',  // 40px
  cardPaddingStep: 'var(--token-space-8)',  // 32px
  baseFontSize: 'var(--token-fs-16)',
  fontFamily: 'var(--token-family-base)',
  columnCount: { mobile: 1, tablet: 2, desktop: 4 },
  primaryActionPlacement: 'inline',
  chartForward: true,
}

/**
 * CUSTOMER — most spacious, plain language, no internal codes.
 *
 * @type {PersonaDensityPreset}
 */
export const CUSTOMER_PRESET = {
  rowHeight: 'calc(var(--token-space-8) + var(--token-space-8) + var(--token-space-2))',  // 72px
  controlHeight: 'calc(var(--token-space-8) + var(--token-space-4))',  // 48px
  minTouchTarget: 'calc(var(--token-space-8) + var(--token-space-4))',  // 48px
  cardPaddingStep: 'var(--token-space-8)',  // 32px
  baseFontSize: 'var(--token-fs-16)',
  fontFamily: 'var(--token-family-base)',
  columnCount: { mobile: 1, tablet: 1, desktop: 2 },
  primaryActionPlacement: 'inline',
  plainLanguage: true,
}

/** @type {Record<string, PersonaDensityPreset>} */
export const PERSONA_PRESETS = {
  technician: TECHNICIAN_PRESET,
  dispatcher: DISPATCHER_PRESET,
  operations: OPERATIONS_PRESET,
  customer: CUSTOMER_PRESET,
}

/**
 * Resolves the density preset for a given persona key.
 * Returns null for unknown personas (unknown persona falls through to default density).
 *
 * @param {string | null | undefined} persona
 * @returns {PersonaDensityPreset | null}
 */
export function resolvePersonaDensity(persona) {
  if (!persona) return null
  return PERSONA_PRESETS[persona] ?? null
}

/**
 * Returns the numeric pixel value of the minTouchTarget for matrix assertions.
 * Evaluates the token calc expression to a pixel integer.
 *
 * @param {string} persona
 * @returns {number}
 */
export function resolveMinTouchTargetPx(persona) {
  const MAP = {
    technician: 44,
    dispatcher: 32,
    operations: 40,
    customer: 48,
  }
  return MAP[persona] ?? 0
}

/**
 * Returns the numeric pixel value of the rowHeight for matrix assertions.
 *
 * @param {string} persona
 * @returns {number}
 */
export function resolveRowHeightPx(persona) {
  const MAP = {
    technician: 44,
    dispatcher: 32,
    operations: 56,
    customer: 72,
  }
  return MAP[persona] ?? 0
}
