/**
 * Shared error-mapping layer for the technician and dispatcher surfaces.
 *
 * Translates the API error envelope { code, message, fieldErrors, traceId }
 * into typed UI states.
 */

/**
 * @typedef {'CONFLICT_REFRESH' | 'GUARD_MESSAGE' | 'FIELD_ERRORS' | 'PERMISSION' | 'DEGRADED' | 'RATE_LIMITED'} MappedErrorType
 *
 * @typedef {{
 *   type: MappedErrorType,
 *   message: string,
 *   fieldErrors?: Array<{field: string, message: string}>,
 *   retryAfterMs?: number | null,
 *   traceId?: string | null,
 * }} MappedError
 */

/**
 * Maps a ClientError from any work-order endpoint to a typed MappedError.
 *
 * @param {import('../../api/errors.js').ClientError} error
 * @returns {MappedError}
 */
export function mapApiError(error) {
  if (!error) return { type: 'DEGRADED', message: 'An unexpected error occurred.' };

  const status = error.status ?? 0;
  const code   = error.code ?? '';

  if (status === 409) {
    return {
      type: 'CONFLICT_REFRESH',
      message: 'This job was updated by someone else. Reloading the latest state.',
      traceId: error.traceId ?? null,
    };
  }

  if (status === 422) {
    const msg = error.fieldErrors?.[0]?.message ?? error.message ?? 'A required condition was not met.';
    return {
      type: 'GUARD_MESSAGE',
      message: msg,
      traceId: error.traceId ?? null,
    };
  }

  if (status === 400) {
    return {
      type: 'FIELD_ERRORS',
      message: error.message ?? 'Invalid request.',
      fieldErrors: error.fieldErrors ?? [],
      traceId: error.traceId ?? null,
    };
  }

  if (status === 403) {
    return {
      type: 'PERMISSION',
      message: 'You do not have permission to perform this action.',
      traceId: error.traceId ?? null,
    };
  }

  if (status === 429) {
    return {
      type: 'RATE_LIMITED',
      message: 'Too many requests. Please wait before retrying.',
      retryAfterMs: error.retryAfterMs ?? null,
      traceId: error.traceId ?? null,
    };
  }

  return {
    type: 'DEGRADED',
    message: 'Service temporarily unavailable. Please try again.',
    retryAfterMs: null,
    traceId: error.traceId ?? null,
  };
}
