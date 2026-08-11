const STORAGE_KEY = 'fsvc_appearance';
const VALID_VALUES = new Set(['light', 'dark', 'system']);

/**
 * Reads the mirrored appearance preference from localStorage.
 * Returns null if missing, corrupt, or an unknown value.
 */
export function readMirror() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === null) return null;
    return VALID_VALUES.has(raw) ? raw : null;
  } catch {
    return null;
  }
}

/**
 * Writes the appearance preference mirror to localStorage.
 * Removes the key when value is null/undefined. Swallows SecurityError.
 * @param {string|null|undefined} value
 */
export function writeMirror(value) {
  try {
    if (value == null) {
      localStorage.removeItem(STORAGE_KEY);
    } else {
      localStorage.setItem(STORAGE_KEY, value);
    }
  } catch {
    // SecurityError in private-browsing or origin-sandboxed contexts — no-op
  }
}
