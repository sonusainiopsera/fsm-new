/**
 * @fileoverview Maps a stored preference value to a concrete rendered appearance.
 *
 * Resolution order (consistent with the server-side and docs/APPEARANCE.md):
 *  1. Server value (LIGHT | DARK | SYSTEM) — authoritative once session is established.
 *  2. Local mirror from localStorage.
 *  3. OS media query (prefers-color-scheme).
 *  4. Fallback: 'light'.
 *
 * 'SYSTEM' defers to the OS media query result, updating live without a server write.
 */

/**
 * @typedef {'light' | 'dark'} ConcreteAppearance
 * @typedef {'LIGHT' | 'DARK' | 'SYSTEM' | null | undefined} PreferenceValue
 */

/**
 * Maps a stored preference value to a concrete appearance string.
 *
 * @param {PreferenceValue} preference
 * @returns {ConcreteAppearance}
 */
export function resolveAppearance(preference) {
  if (preference === 'DARK') return 'dark'
  if (preference === 'LIGHT') return 'light'
  if (preference === 'SYSTEM') return osPrefersDark() ? 'dark' : 'light'
  // null / undefined → default to light
  return 'light'
}

/**
 * Returns true when the OS prefers dark mode.
 *
 * @returns {boolean}
 */
export function osPrefersDark() {
  return (
    typeof window !== 'undefined' &&
    window.matchMedia != null &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

/**
 * Sets the {@code data-appearance} attribute on the {@code <html>} element.
 * Called once at pre-paint and again whenever the preference changes.
 *
 * @param {ConcreteAppearance} concrete
 */
export function applyAppearance(concrete) {
  if (typeof document !== 'undefined') {
    if (concrete === 'dark') {
      document.documentElement.setAttribute('data-appearance', 'dark')
    } else {
      document.documentElement.removeAttribute('data-appearance')
    }
  }
}
