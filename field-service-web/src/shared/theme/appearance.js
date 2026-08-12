/**
 * Per-user appearance persistence for the technician surface.
 *
 * Keys localStorage by user ID so switching accounts on a shared device
 * gives each technician their own light/dark preference without leaking state.
 *
 * Falls back to the global mirror (fsvc_appearance) when no user ID is
 * available (e.g. before auth completes).
 *
 * Constraint: access tokens must never be written to localStorage.
 * This module stores only 'light' | 'dark' | 'system' preference strings.
 */

const KEY_PREFIX    = 'fsvc_appearance_u_';
const FALLBACK_KEY  = 'fsvc_appearance';
const VALID_VALUES  = new Set(['light', 'dark', 'system']);

/**
 * Reads the stored appearance preference for a user.
 * @param {string|null|undefined} userId
 * @returns {'light'|'dark'|'system'|null}
 */
export function readUserAppearance(userId) {
  try {
    const key = userId ? `${KEY_PREFIX}${userId}` : FALLBACK_KEY;
    const raw = localStorage.getItem(key);
    return raw && VALID_VALUES.has(raw) ? /** @type {any} */ (raw) : null;
  } catch {
    return null;
  }
}

/**
 * Persists the appearance preference for a user.
 * Passing null removes the stored value.
 * @param {string|null|undefined} userId
 * @param {'light'|'dark'|'system'|null} value
 */
export function writeUserAppearance(userId, value) {
  try {
    const key = userId ? `${KEY_PREFIX}${userId}` : FALLBACK_KEY;
    if (!value) {
      localStorage.removeItem(key);
    } else {
      localStorage.setItem(key, value);
    }
  } catch {
    // SecurityError in private browsing or storage quota exceeded — degrade silently
  }
}

/**
 * Clears the stored preference for a user (e.g. on account switch).
 * @param {string|null|undefined} userId
 */
export function clearUserAppearance(userId) {
  writeUserAppearance(userId, null);
}

/**
 * Resolves a stored preference value to a concrete 'light' | 'dark' string.
 * Resolution order: 'dark' → 'dark', 'light' → 'light',
 * 'system' → OS preference, null/unknown → 'light' (per design rule).
 * @param {string|null|undefined} value
 * @returns {'light'|'dark'}
 */
export function resolveUserAppearance(value) {
  const v = value ? value.toLowerCase() : null;
  if (v === 'dark')   return 'dark';
  if (v === 'light')  return 'light';
  if (v === 'system') {
    try {
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    } catch {
      return 'light';
    }
  }
  return 'light'; // default per design rule
}
