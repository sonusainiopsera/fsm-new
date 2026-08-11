/**
 * @fileoverview Normalised error model for the data layer.
 *
 * Converts the platform's structured error envelope
 *   { code, message, fieldErrors, traceId }
 * into a typed ClientError with a `retryable` flag.
 *
 * RULE: retryable is always false for 4xx. Retrying a 422 guard refusal or a
 * 409 illegal transition is a form of failing open (A10 / WO-186 constraint).
 */

/**
 * @typedef {{
 *   field: string,
 *   message: string
 * }} FieldError
 */

/**
 * @typedef {{
 *   status: number,
 *   code: string,
 *   message: string,
 *   fieldErrors: FieldError[],
 *   traceId: string | null,
 *   retryable: boolean
 * }} ClientError
 */

/** Status codes that are never retried (includes all 4xx). */
const NON_RETRYABLE_STATUSES = new Set([
  400, 401, 403, 404, 409, 410, 422, 429,
])

/**
 * Normalises a platform error envelope into a ClientError.
 *
 * @param {number} status  HTTP status code
 * @param {unknown} body   Parsed JSON response body (may be malformed)
 * @returns {ClientError}
 */
export function normaliseError(status, body) {
  const envelope = isEnvelope(body) ? body : {}
  return {
    status,
    code: envelope.code ?? httpCodeFor(status),
    message: envelope.message ?? defaultMessageFor(status),
    fieldErrors: Array.isArray(envelope.fieldErrors) ? envelope.fieldErrors : [],
    traceId: envelope.traceId ?? null,
    retryable: isRetryable(status),
  }
}

/**
 * Creates a ClientError for a network-level failure (fetch threw).
 * Network errors are retryable.
 *
 * @param {unknown} cause  The original thrown error
 * @returns {ClientError}
 */
export function networkError(cause) {
  return {
    status: 0,
    code: 'NETWORK_ERROR',
    message: cause instanceof Error ? cause.message : 'Network request failed',
    fieldErrors: [],
    traceId: null,
    retryable: true,
  }
}

/**
 * Returns true if the given status code is retryable.
 *
 * 4xx → never retryable (includes 409, 422, 429).
 * 5xx → retryable.
 * 0 (network error) → retryable.
 *
 * @param {number} status
 * @returns {boolean}
 */
export function isRetryable(status) {
  if (status === 0) return true          // network failure
  if (NON_RETRYABLE_STATUSES.has(status)) return false
  return status >= 500                   // 5xx
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * @param {unknown} body
 * @returns {body is { code?: unknown, message?: unknown, fieldErrors?: unknown, traceId?: unknown }}
 */
function isEnvelope(body) {
  return body !== null && typeof body === 'object'
}

/**
 * @param {number} status
 * @returns {string}
 */
function httpCodeFor(status) {
  const MAP = {
    400: 'VALIDATION_FAILED',
    401: 'UNAUTHENTICATED',
    403: 'FORBIDDEN',
    404: 'NOT_FOUND',
    409: 'CONFLICT',
    422: 'UNPROCESSABLE',
    429: 'RATE_LIMITED',
    503: 'SERVICE_UNAVAILABLE',
    500: 'INTERNAL_ERROR',
  }
  return MAP[status] ?? `HTTP_${status}`
}

/**
 * @param {number} status
 * @returns {string}
 */
function defaultMessageFor(status) {
  const MAP = {
    400: 'The request contained invalid data.',
    401: 'Authentication required.',
    403: 'Access denied.',
    404: 'The requested resource was not found.',
    409: 'The operation conflicts with the current resource state.',
    422: 'The operation was refused by a business rule.',
    429: 'Too many requests. Please wait and try again.',
    503: 'A required service is temporarily unavailable.',
    500: 'An unexpected error occurred.',
  }
  return MAP[status] ?? `HTTP ${status}`
}
