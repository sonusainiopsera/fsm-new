import { readMirror } from './appearanceMirror.js';

/**
 * Resolves a stored preference value (from localStorage or the API) to a
 * concrete 'light' | 'dark' string suitable for the data-appearance attribute.
 *
 * Resolution order:
 *   'dark'   → 'dark'
 *   'light'  → 'light'
 *   'system' → OS prefers-color-scheme (dark → 'dark', else → 'light')
 *   null / unknown → 'light'
 *
 * Values are compared case-insensitively so API enum strings ('DARK') and
 * localStorage strings ('dark') are both accepted.
 *
 * @param {string|null|undefined} storedValue
 * @returns {'light'|'dark'}
 */
export function resolveAppearance(storedValue) {
  const v = storedValue ? storedValue.toLowerCase() : null;
  if (v === 'dark') return 'dark';
  if (v === 'light') return 'light';
  if (v === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return 'light';
}

/**
 * Convenience wrapper: reads the localStorage mirror and resolves it.
 * Safe to call synchronously in pre-paint context (no async I/O).
 * @returns {'light'|'dark'}
 */
export function resolveFromMirror() {
  return resolveAppearance(readMirror());
}
