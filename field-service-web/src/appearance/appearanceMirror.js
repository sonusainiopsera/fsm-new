/**
 * @fileoverview Local mirror for the user's appearance preference.
 *
 * Stores only the enum value ('LIGHT' | 'DARK' | 'SYSTEM') under a fixed
 * localStorage key.  No personal data is written.  The server value is
 * authoritative once the session is established; this mirror is a best-effort
 * cache for pre-paint resolution.
 *
 * Data classification: Internal (BR-23) — non-sensitive, no personal data.
 */

export const STORAGE_KEY = 'fs-appearance'

const VALID_VALUES = new Set(['LIGHT', 'DARK', 'SYSTEM'])

/**
 * Reads the mirrored preference from localStorage.
 *
 * @returns {'LIGHT' | 'DARK' | 'SYSTEM' | null} stored value or null if absent/invalid
 */
export function readMirror() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw && VALID_VALUES.has(raw)) {
      return /** @type {'LIGHT' | 'DARK' | 'SYSTEM'} */ (raw)
    }
    if (raw != null) {
      // Tampered or unknown value — clear and fall back
      localStorage.removeItem(STORAGE_KEY)
    }
    return null
  } catch {
    // localStorage unavailable (e.g. private browsing restrictions)
    return null
  }
}

/**
 * Writes the preference to localStorage.
 *
 * @param {'LIGHT' | 'DARK' | 'SYSTEM'} value
 */
export function writeMirror(value) {
  if (!VALID_VALUES.has(value)) {
    throw new Error(`Invalid appearance value: ${value}`)
  }
  try {
    localStorage.setItem(STORAGE_KEY, value)
  } catch {
    // Ignore write failures (storage quota, private browsing)
  }
}

/**
 * Removes the mirrored preference (e.g. on sign-out).
 */
export function clearMirror() {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Ignore
  }
}
