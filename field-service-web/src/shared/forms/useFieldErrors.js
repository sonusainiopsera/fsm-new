/**
 * useFieldErrors — maps a platform API fieldErrors array onto form field names.
 *
 * Platform error envelope:
 *   { code, message, fieldErrors: [{ field, message }], traceId }
 *
 * Usage:
 *   const { errorsFor, summaryErrors, traceId } = useFieldErrors(error);
 *   errorsFor('email') → string[]   (messages for that field)
 *   summaryErrors      → string[]   (messages not matched to any known field)
 *   traceId            → string | null
 *
 * A field name must be explicitly registered via the `knownFields` argument so
 * that an API field name that has no matching form input is routed to the
 * summary region rather than silently dropped.
 *
 * @module shared/forms/useFieldErrors
 */

import { useMemo } from 'react';

/**
 * @typedef {{ field: string, message: string }} ApiFieldError
 *
 * @typedef {{
 *   errorsFor: (field: string) => string[],
 *   summaryErrors: string[],
 *   traceId: string | null,
 *   hasErrors: boolean,
 * }} FieldErrorState
 */

/**
 * Maps a ClientError (or any object with a fieldErrors array) onto form field names.
 *
 * @param {import('../../api/errors.js').ClientError | null | undefined} error
 * @param {string[]} [knownFields]  Fields that exist in the form; unmatched errors go to summary
 * @returns {FieldErrorState}
 */
export function useFieldErrors(error, knownFields = []) {
  return useMemo(() => {
    if (!error || !Array.isArray(error.fieldErrors)) {
      return { errorsFor: () => [], summaryErrors: [], traceId: null, hasErrors: false };
    }

    const knownSet = new Set(knownFields);
    /** @type {Map<string, string[]>} */
    const byField = new Map();
    /** @type {string[]} */
    const summaryErrors = [];

    for (const fe of error.fieldErrors) {
      if (!fe || typeof fe.field !== 'string') continue;
      const msg = typeof fe.message === 'string' ? fe.message : String(fe.message ?? '');
      if (knownSet.size > 0 && !knownSet.has(fe.field)) {
        // Unmatched field → summary region
        summaryErrors.push(msg);
        continue;
      }
      const list = byField.get(fe.field) ?? [];
      list.push(msg);
      byField.set(fe.field, list);
    }

    // If the top-level message is present and there are no field errors, show it in summary
    if (error.fieldErrors.length === 0 && error.message && error.status >= 400) {
      summaryErrors.push(error.message);
    }

    return {
      errorsFor: (field) => byField.get(field) ?? [],
      summaryErrors,
      traceId: error.traceId ?? null,
      hasErrors: byField.size > 0 || summaryErrors.length > 0,
    };
  }, [error, knownFields]);
}

/**
 * Standalone (non-hook) version for use outside React components.
 *
 * @param {import('../../api/errors.js').ClientError | null | undefined} error
 * @param {string[]} [knownFields]
 * @returns {FieldErrorState}
 */
export function mapFieldErrors(error, knownFields = []) {
  if (!error || !Array.isArray(error.fieldErrors)) {
    return { errorsFor: () => [], summaryErrors: [], traceId: null, hasErrors: false };
  }

  const knownSet = new Set(knownFields);
  /** @type {Map<string, string[]>} */
  const byField = new Map();
  /** @type {string[]} */
  const summaryErrors = [];

  for (const fe of error.fieldErrors) {
    if (!fe || typeof fe.field !== 'string') continue;
    const msg = typeof fe.message === 'string' ? fe.message : String(fe.message ?? '');
    if (knownSet.size > 0 && !knownSet.has(fe.field)) {
      summaryErrors.push(msg);
      continue;
    }
    const list = byField.get(fe.field) ?? [];
    list.push(msg);
    byField.set(fe.field, list);
  }

  if (error.fieldErrors.length === 0 && error.message && error.status >= 400) {
    summaryErrors.push(error.message);
  }

  return {
    errorsFor: (field) => byField.get(field) ?? [],
    summaryErrors,
    traceId: error.traceId ?? null,
    hasErrors: byField.size > 0 || summaryErrors.length > 0,
  };
}
