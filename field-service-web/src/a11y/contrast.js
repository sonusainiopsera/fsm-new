/**
 * @fileoverview WCAG 2.1 contrast ratio calculator for token-based colour pairings.
 *
 * Usage:
 *   import { assertPairings } from './contrast.js'
 *   assertPairings(pairings, LIGHT_TOKEN_VALUES, 'light')   // throws on failure
 *   assertPairings(pairings, DARK_TOKEN_VALUES, 'dark')
 *
 * New token pairings must be declared in src/a11y/contrastPairings.json to be checked.
 * The audit fails on undeclared pairings rather than silently skipping them.
 */

/**
 * Resolved token → hex colour maps for both appearances.
 * These are derived directly from tokens.light.css and tokens.dark.css.
 * Kept in sync by the audit step; structural token changes require updating both.
 */
export const LIGHT_TOKEN_VALUES = {
  '--token-text-primary': '#111827',
  '--token-text-secondary': '#6b7280',
  '--token-text-disabled': '#9ca3af',
  '--token-text-on-accent': '#ffffff',
  '--token-surface-page': '#f9fafb',
  '--token-surface-card': '#ffffff',
  '--token-surface-elevated': '#ffffff',
  '--token-accent-600': '#2563eb',
  '--token-accent-500': '#3b82f6',
  '--token-info-subtle': '#eff6ff',
  '--token-info-emphasis': '#1d4ed8',
  '--token-success-subtle': '#f0fdf4',
  '--token-success-emphasis': '#16a34a',
  '--token-warning-subtle': '#fffbeb',
  '--token-warning-emphasis': '#d97706',
  '--token-danger-subtle': '#fef2f2',
  '--token-danger-emphasis': '#dc2626',
  '--token-border-focus': '#3b82f6',
}

export const DARK_TOKEN_VALUES = {
  '--token-text-primary': '#f9fafb',
  '--token-text-secondary': '#9ca3af',
  '--token-text-disabled': '#6b7280',
  '--token-text-on-accent': '#ffffff',
  '--token-surface-page': '#111827',
  '--token-surface-card': '#1f2937',
  '--token-surface-elevated': '#374151',
  '--token-accent-600': '#93c5fd',
  '--token-accent-500': '#60a5fa',
  '--token-info-subtle': '#1e3a5f',
  '--token-info-emphasis': '#93c5fd',
  '--token-success-subtle': '#14532d',
  '--token-success-emphasis': '#86efac',
  '--token-warning-subtle': '#451a03',
  '--token-warning-emphasis': '#fde68a',
  '--token-danger-subtle': '#450a0a',
  '--token-danger-emphasis': '#fca5a5',
  '--token-border-focus': '#60a5fa',
}

/**
 * Parses a CSS hex colour string to [R, G, B] in range [0, 255].
 *
 * @param {string} hex  e.g. '#ffffff' or '#fff'
 * @returns {[number, number, number]}
 */
export function parseHex(hex) {
  const clean = hex.replace('#', '')
  if (clean.length === 3) {
    const [r, g, b] = clean.split('').map(c => parseInt(c + c, 16))
    return [r, g, b]
  }
  const r = parseInt(clean.slice(0, 2), 16)
  const g = parseInt(clean.slice(2, 4), 16)
  const b = parseInt(clean.slice(4, 6), 16)
  return [r, g, b]
}

/**
 * Computes the WCAG relative luminance for a single linearized channel value (0–1).
 *
 * @param {number} val  channel value in [0, 1]
 * @returns {number}
 */
function linearize(val) {
  const c = val / 255
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
}

/**
 * Computes WCAG 2.1 relative luminance for an RGB triple.
 *
 * @param {[number, number, number]} rgb
 * @returns {number}  luminance in [0, 1]
 */
export function relativeLuminance([r, g, b]) {
  return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
}

/**
 * Computes the WCAG 2.1 contrast ratio between two hex colour values.
 *
 * @param {string} fgHex
 * @param {string} bgHex
 * @returns {number}  contrast ratio in [1, 21]
 */
export function computeContrastRatio(fgHex, bgHex) {
  const L1 = relativeLuminance(parseHex(fgHex))
  const L2 = relativeLuminance(parseHex(bgHex))
  const lighter = Math.max(L1, L2)
  const darker = Math.min(L1, L2)
  return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Resolves a token name to its hex value in the given value set.
 * Throws if the token is not found (undeclared pairings are build failures).
 *
 * @param {string} tokenName  e.g. '--token-text-primary'
 * @param {Record<string, string>} valueSet  LIGHT_TOKEN_VALUES or DARK_TOKEN_VALUES
 * @returns {string}  hex colour
 */
export function resolveTokenColor(tokenName, valueSet) {
  const value = valueSet[tokenName]
  if (!value) {
    throw new Error(
      `[contrast] Token "${tokenName}" is not in the resolved value set. ` +
      `Add it to LIGHT_TOKEN_VALUES / DARK_TOKEN_VALUES in contrast.js.`
    )
  }
  return value
}

/**
 * Checks all declared pairings against the given token value set and appearance.
 *
 * @param {import('./contrastPairings.json')} manifest
 * @param {Record<string, string>} valueSet
 * @param {'light' | 'dark'} appearance
 * @param {string} [persona]  if supplied, only pairings for this persona are checked
 * @returns {{ failures: Array<{ name: string, ratio: number, minRatio: number, fg: string, bg: string }> }}
 */
export function checkPairings(manifest, valueSet, appearance, persona) {
  const failures = []

  for (const pairing of manifest.pairings) {
    if (!pairing.appearances.includes(appearance)) continue
    if (persona && !pairing.personas.includes(persona)) continue

    const fgHex = resolveTokenColor(pairing.fg, valueSet)
    const bgHex = resolveTokenColor(pairing.bg, valueSet)
    const ratio = computeContrastRatio(fgHex, bgHex)

    if (ratio < pairing.minRatio) {
      failures.push({ name: pairing.name, ratio: Math.round(ratio * 100) / 100, minRatio: pairing.minRatio, fg: pairing.fg, bg: pairing.bg })
    }
  }

  return { failures }
}

/**
 * Asserts all pairings pass. Throws with a diagnostic message if any fail.
 *
 * @param {import('./contrastPairings.json')} manifest
 * @param {Record<string, string>} valueSet
 * @param {'light' | 'dark'} appearance
 * @param {string} [persona]
 */
export function assertPairings(manifest, valueSet, appearance, persona) {
  const { failures } = checkPairings(manifest, valueSet, appearance, persona)
  if (failures.length > 0) {
    const detail = failures
      .map(f => `  "${f.name}": ${f.ratio.toFixed(2)}:1 (requires ${f.minRatio}:1) — ${f.fg} on ${f.bg}`)
      .join('\n')
    throw new Error(
      `[contrast] ${failures.length} pairing(s) failed WCAG ${appearance} contrast check:\n${detail}`
    )
  }
}
