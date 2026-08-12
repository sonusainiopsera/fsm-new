/**
 * @fileoverview Error mapping — translates ClientError into typed UI states.
 *
 * The API error envelope { code, message, fieldErrors, traceId } maps to exactly
 * five distinct UI outcomes:
 *
 *   conflict-refresh   409  — job state changed underneath the caller; refetch and re-render
 *   guard-message      422  — business rule refused; show the server message verbatim
 *   field-error        400  — validation failed; surface fieldErrors to form fields
 *   permission         403  — access denied; generic notice, no existence disclosure
 *   degraded-retry     503  — service unavailable; show retry affordance
 *
 * RULE: Never map 409 or 422 as retryable (WO-186 constraint — retrying a refused
 * transition is a form of failing open).
 *
 * @module shared/api/errorMapping
 */

// ── Public types (JSDoc) ──────────────────────────────────────────────────────

/**
 * @typedef {'conflict-refresh' | 'guard-message' | 'field-error' | 'permission' | 'degraded-retry' | 'unknown'} UIErrorKind
 */

/**
 * @typedef {{
 *   kind: UIErrorKind,
 *   message: string,
 *   fieldErrors: import('../api/errors.js').FieldError[],
 *   traceId: string | null,
 *   shouldRefetch: boolean,
 * }} MappedError
 */

// ── Core mapping ──────────────────────────────────────────────────────────────

/**
 * Maps a ClientError to a MappedError suitable for rendering in the UI.
 *
 * @param {import('../../api/errors.js').ClientError} error
 * @returns {MappedError}
 */
export function mapApiError(error) {
  if (!error || typeof error.status !== 'number') {
    return unknownError(error)
  }

  switch (error.status) {
    case 409:
      return {
        kind: 'conflict-refresh',
        message: 'The job status changed while you had it open. The screen has been refreshed.',
        fieldErrors: [],
        traceId: error.traceId,
        shouldRefetch: true,
      }

    case 422:
      return {
        kind: 'guard-message',
        message: error.message || 'The action was refused. Check requirements and try again.',
        fieldErrors: [],
        traceId: error.traceId,
        shouldRefetch: false,
      }

    case 400:
      return {
        kind: 'field-error',
        message: error.message || 'Please check the highlighted fields and try again.',
        fieldErrors: Array.isArray(error.fieldErrors) ? error.fieldErrors : [],
        traceId: error.traceId,
        shouldRefetch: false,
      }

    case 403:
      return {
        kind: 'permission',
        message: 'You do not have permission to perform this action.',
        fieldErrors: [],
        traceId: error.traceId,
        shouldRefetch: false,
      }

    case 503:
    case 0: // network error
      return {
        kind: 'degraded-retry',
        message: 'Service temporarily unavailable. Please try again.',
        fieldErrors: [],
        traceId: error.traceId ?? null,
        shouldRefetch: false,
      }

    default:
      return unknownError(error)
  }
}

/**
 * Returns true if the error kind requires a data refetch.
 *
 * @param {MappedError} mapped
 * @returns {boolean}
 */
export function requiresRefetch(mapped) {
  return mapped.shouldRefetch === true
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * @param {unknown} error
 * @returns {MappedError}
 */
function unknownError(error) {
  const msg = (error && error.message) ? error.message : 'An unexpected error occurred.'
  return {
    kind: 'unknown',
    message: msg,
    fieldErrors: [],
    traceId: (error && error.traceId) ? error.traceId : null,
    shouldRefetch: false,
  }
}
