/**
 * @fileoverview Per-attempt idempotency key management.
 *
 * Contract (AC-6):
 * - generateAttemptKey() returns a new UUID for a genuinely new submission.
 * - The caller holds the key in component state and passes it to every retry
 *   of the same attempt — the key must NOT be regenerated on retry.
 * - Only call generateAttemptKey() when beginning an entirely new submission
 *   (e.g. after a successful commit or after the user explicitly cancels and
 *   starts a fresh submission).
 *
 * Usage pattern:
 *   const [idempotencyKey, setIdempotencyKey] = useState(() => generateAttemptKey())
 *   // On retry: pass the same idempotencyKey — do NOT call setIdempotencyKey
 *   // After success: setIdempotencyKey(generateAttemptKey()) for the next submission
 */

/**
 * Generates a fresh idempotency key for a new submission attempt.
 * Uses crypto.randomUUID when available; falls back to a hex string.
 *
 * @returns {string} A UUID-shaped unique key
 */
export function generateAttemptKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Fallback for test environments that stub crypto
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.floor(Math.random() * 16)
    const v = c === 'x' ? r : (r & 0x3 | 0x8)
    return v.toString(16)
  })
}

/**
 * Returns an object that holds one idempotency key per submission attempt.
 *
 * Designed for plain-JS contexts (e.g. outside React) where useState is
 * not available. For React components, store the key in useState directly
 * using generateAttemptKey() as the initialiser.
 *
 * @returns {{ key: string, advance: () => void }}
 */
export function createAttemptKeyHolder() {
  let _key = generateAttemptKey()
  return {
    /** The current attempt key — reuse on retry. */
    get key() { return _key },
    /** Advance to a fresh key for the next genuinely new submission. */
    advance() { _key = generateAttemptKey() },
  }
}
