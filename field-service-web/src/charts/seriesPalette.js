/**
 * @fileoverview Colour-blind-safe Recharts series palette bound to design tokens.
 *
 * The palette uses seven distinct hues from the token set that remain
 * distinguishable under deuteranopia, protanopia and tritanopia.
 * Each series also carries a distinct shape for greyscale survivability (BR-34).
 *
 * Usage with Recharts:
 *   import { SERIES_PALETTE, getSeriesColor, getSeriesShape } from './seriesPalette.js'
 *   <Line stroke={getSeriesColor(0)} dot={{ shape: getSeriesShape(0) }} />
 */

/**
 * CVD-safe palette mapping to CSS token references.
 * Ordered so adjacent series are maximally distinguishable.
 *
 * @type {Array<{ color: string, darkColor: string, shape: string, label: string }>}
 */
export const SERIES_PALETTE = [
  {
    color: 'var(--token-accent-600)',     // blue — #2563eb light
    darkColor: 'var(--token-accent-500)', // lighter blue in dark
    shape: 'circle',
    label: 'Series 1',
  },
  {
    color: 'var(--token-warning-default)',     // amber — #f59e0b light
    darkColor: 'var(--token-warning-default)', // #fbbf24 dark
    shape: 'square',
    label: 'Series 2',
  },
  {
    color: 'var(--token-success-emphasis)',      // dark green — #16a34a light
    darkColor: 'var(--token-success-emphasis)', // #86efac dark
    shape: 'triangle',
    label: 'Series 3',
  },
  {
    color: 'var(--token-danger-default)',      // red — #ef4444 light
    darkColor: 'var(--token-danger-default)', // #f87171 dark
    shape: 'diamond',
    label: 'Series 4',
  },
  {
    color: 'var(--token-neutral-600)',      // dark grey — #4b5563 light
    darkColor: 'var(--token-neutral-400)', // lighter grey in dark
    shape: 'cross',
    label: 'Series 5',
  },
  {
    color: 'var(--token-accent-300)',      // light blue — #93c5fd light
    darkColor: 'var(--token-accent-700)', // inverted in dark
    shape: 'star',
    label: 'Series 6',
  },
  {
    color: 'var(--token-info-emphasis)',       // deep blue — #1d4ed8 light
    darkColor: 'var(--token-info-emphasis)', // #93c5fd dark
    shape: 'wye',
    label: 'Series 7',
  },
]

/**
 * Returns the CSS token reference for a series index (wraps around).
 *
 * @param {number} index
 * @param {'light' | 'dark'} [appearance='light']
 * @returns {string}  CSS custom property value e.g. 'var(--token-accent-600)'
 */
export function getSeriesColor(index, appearance = 'light') {
  const entry = SERIES_PALETTE[index % SERIES_PALETTE.length]
  return appearance === 'dark' ? entry.darkColor : entry.color
}

/**
 * Returns the Recharts dot/shape type for a series index (wraps around).
 *
 * @param {number} index
 * @returns {string}  shape type e.g. 'circle', 'square', 'triangle'
 */
export function getSeriesShape(index) {
  return SERIES_PALETTE[index % SERIES_PALETTE.length].shape
}

/**
 * Returns a stroke-dasharray pattern for a series index.
 * Provides a second non-colour differentiator for line charts.
 *
 * @param {number} index
 * @returns {string}
 */
export function getSeriesDash(index) {
  const PATTERNS = ['', '5 5', '10 5', '5 10', '15 5', '5 15', '10 5 5 5']
  return PATTERNS[index % PATTERNS.length]
}

/**
 * Resolves CSS custom property values to their computed hex equivalents.
 * Used by the tabular equivalent to annotate values without colour dependency.
 * For Recharts, pass these via the `fill` / `stroke` props directly.
 *
 * @param {string} cssVar  e.g. 'var(--token-accent-600)'
 * @param {Element} [element]  element to compute against; defaults to document.body
 * @returns {string}  resolved CSS value or the cssVar unchanged if no document
 */
export function resolveSeriesColor(cssVar, element) {
  if (typeof document === 'undefined') return cssVar
  const el = element ?? document.body
  const match = cssVar.match(/^var\((--[^)]+)\)$/)
  if (!match) return cssVar
  return getComputedStyle(el).getPropertyValue(match[1]).trim() || cssVar
}
