/**
 * Per-attempt idempotency key helper.
 *
 * Rules:
 * - One UUID is generated per submission attempt (call newAttemptKey()).
 * - That key is reused verbatim on every retry of the SAME attempt.
 * - Only a genuinely new submission gets a new key (call newAttemptKey() again).
 *
 * The key is sent in the Idempotency-Key header so a network-level retry
 * cannot double-consume inventory (AC-6).
 */

/**
 * Generates a new per-attempt idempotency key.
 * Store the returned value in component state and pass it to every retry of
 * that attempt; call this function only when starting a fresh submission.
 *
 * @returns {string} UUID v4
 */
export function newAttemptKey() {
  return crypto.randomUUID();
}
