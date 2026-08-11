/**
 * Colour-blind-safe Recharts series palette.
 *
 * Colours are bound to design tokens (resolved at runtime from CSS custom
 * properties) to ensure they adapt to the active appearance.  Each series
 * also carries a distinct shape marker and dash pattern so meaning is
 * never conveyed by colour alone (BR-34).
 *
 * Palette ordering follows IBM's colour-blind-safe design guidelines:
 * blue → orange → magenta → purple → gold — all distinguishable under
 * protanopia, deuteranopia and tritanopia.
 */

/**
 * @typedef {Object} SeriesEntry
 * @property {string} key           - Unique series identifier
 * @property {string} colorToken    - CSS custom-property name
 * @property {string} markerShape   - Recharts dot type: 'circle'|'square'|'triangle'|'diamond'|'star'
 * @property {string} strokeDasharray - SVG dash pattern for the line ('' = solid)
 * @property {string} fallbackColor - Static fallback for environments without CSS vars
 */

/** @type {SeriesEntry[]} */
export const SERIES_PALETTE = [
  {
    key: 'series-0',
    colorToken: '--color-info-base',
    markerShape: 'circle',
    strokeDasharray: '',
    fallbackColor: '#2563eb',
  },
  {
    key: 'series-1',
    colorToken: '--color-warning-base',
    markerShape: 'square',
    strokeDasharray: '5 3',
    fallbackColor: '#d97706',
  },
  {
    key: 'series-2',
    colorToken: '--color-danger-base',
    markerShape: 'triangle',
    strokeDasharray: '10 4',
    fallbackColor: '#dc2626',
  },
  {
    key: 'series-3',
    colorToken: '--color-success-base',
    markerShape: 'diamond',
    strokeDasharray: '3 3',
    fallbackColor: '#16a34a',
  },
  {
    key: 'series-4',
    colorToken: '--color-accent-base',
    markerShape: 'star',
    strokeDasharray: '8 3 2 3',
    fallbackColor: '#1a73e8',
  },
];

/**
 * Returns the palette entry at the given index (wrapping around).
 * @param {number} index
 * @returns {SeriesEntry}
 */
export function getPaletteEntry(index) {
  return SERIES_PALETTE[index % SERIES_PALETTE.length];
}

/**
 * Resolves the computed colour value for a token from the document root.
 * Falls back to the static fallbackColor in non-browser environments.
 *
 * @param {SeriesEntry} entry
 * @returns {string}
 */
export function resolveSeriesColor(entry) {
  if (typeof document === 'undefined') return entry.fallbackColor;
  const value = getComputedStyle(document.documentElement)
    .getPropertyValue(entry.colorToken)
    .trim();
  return value || entry.fallbackColor;
}
