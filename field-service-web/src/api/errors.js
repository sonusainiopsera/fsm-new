/**
 * Normalised client error model.
 *
 * Platform envelope: { code, message, fieldErrors?, traceId? }
 *
 * Invariant: 4xx responses are NEVER retryable.
 * Retrying a 422 guard refusal or a 409 illegal transition amounts to
 * failing open (A10). Only 5xx and network errors (status 0) are retryable.
 */

/**
 * @typedef {{ field: string, message: string }} FieldError
 */

export class ClientError extends Error {
  /**
   * @param {number} status           HTTP status code, or 0 for network errors
   * @param {string} code             Machine-readable error code
   * @param {string} message          Human-readable description
   * @param {FieldError[]} fieldErrors
   * @param {string | null} traceId
   * @param {number | null} retryAfterMs  Milliseconds from Retry-After header
   */
  constructor(status, code, message, fieldErrors = [], traceId = null, retryAfterMs = null) {
    super(message);
    this.name = 'ClientError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.traceId = traceId;
    /** True for 5xx and network errors. ALWAYS false for 4xx. */
    this.retryable = status === 0 || status >= 500;
    this.retryAfterMs = retryAfterMs;
  }
}

/**
 * Parses the Retry-After header into milliseconds.
 * Handles delta-seconds (integer) and HTTP-date formats.
 * @param {string | null} header
 * @returns {number | null}
 */
function parseRetryAfter(header) {
  if (!header) return null;
  const seconds = parseInt(header, 10);
  if (!isNaN(seconds)) return seconds * 1000;
  const date = new Date(header);
  if (!isNaN(date.getTime())) return Math.max(0, date.getTime() - Date.now());
  return null;
}

/**
 * Normalises a fetch Response and pre-parsed body into a ClientError.
 *
 * @param {Response} response
 * @param {unknown} body  Pre-parsed JSON body, or null
 * @returns {ClientError}
 */
export function normalizeError(response, body) {
  const status = response.status;
  const retryAfterMs = parseRetryAfter(response.headers.get('Retry-After'));
  const code = (body && typeof body === 'object' && body.code) ? body.code : _defaultCode(status);
  const message = (body && typeof body === 'object' && body.message) ? body.message : _defaultMessage(status);
  const fieldErrors = (body && Array.isArray(body.fieldErrors)) ? body.fieldErrors : [];
  const traceId = (body && typeof body === 'object' && body.traceId) ? body.traceId : null;
  return new ClientError(status, code, message, fieldErrors, traceId, retryAfterMs);
}

/**
 * Wraps a network-level failure (fetch threw, e.g. no connectivity) into a
 * ClientError with status 0 and retryable = true.
 * @param {Error} cause
 * @returns {ClientError}
 */
export function networkError(cause) {
  return new ClientError(0, 'NETWORK_ERROR', cause?.message ?? 'Network error', [], null, null);
}

function _defaultCode(status) {
  const map = {
    400: 'BAD_REQUEST',
    401: 'UNAUTHENTICATED',
    403: 'FORBIDDEN',
    404: 'NOT_FOUND',
    409: 'CONFLICT',
    422: 'UNPROCESSABLE_ENTITY',
    429: 'RATE_LIMITED',
    503: 'SERVICE_UNAVAILABLE',
  };
  return map[status] ?? 'UNEXPECTED_ERROR';
}

function _defaultMessage(status) {
  const map = {
    400: 'The request contained invalid data.',
    401: 'Authentication is required.',
    403: 'You do not have permission to access this resource.',
    404: 'The requested resource was not found.',
    409: 'The request conflicts with the current state of the resource.',
    422: 'The request was understood but could not be processed.',
    429: 'Too many requests. Please wait before retrying.',
    503: 'The service is temporarily unavailable.',
  };
  return map[status] ?? 'An unexpected error occurred.';
}
