/**
 * @fileoverview Utility for binding server-side 400 fieldErrors to form fields.
 *
 * Maps the API's fieldErrors array onto a per-field record so each FormField
 * primitive receives only its own errors. Unmapped errors (fields not present
 * in the current form) are collected into a summary array alongside the traceId.
 *
 * Usage:
 *   const { getFieldErrors, summaryErrors } = useFieldErrors(clientError, KNOWN_FIELDS)
 *   <FormField label="Code" errors={getFieldErrors('code')} />
 *   {summaryErrors.length > 0 && <ErrorSummary errors={summaryErrors} traceId={traceId} />}
 *
 * @module shared/forms/useFieldErrors
 */
import { useMemo } from 'react'

/**
 * @typedef {{ field: string, message: string }} FieldError
 * @typedef {{ message: string, traceId: string | null }} SummaryError
 */

/**
 * @typedef {{
 *   getFieldErrors: (fieldName: string) => string[],
 *   summaryErrors: SummaryError[],
 *   traceId: string | null
 * }} UseFieldErrorsResult
 */

/**
 * Binds server field errors to form fields.
 *
 * @param {import('../../api/errors.js').ClientError | null} error
 * @param {string[]} knownFields  Field names present in the current form
 * @returns {UseFieldErrorsResult}
 */
export function useFieldErrors(error, knownFields) {
  return useMemo(() => {
    if (!error || !error.fieldErrors || error.fieldErrors.length === 0) {
      return {
        getFieldErrors: () => [],
        summaryErrors: error && error.status === 400
          ? [{ message: error.message, traceId: error.traceId }]
          : [],
        traceId: error?.traceId ?? null,
      }
    }

    const knownSet = new Set(knownFields)

    /** @type {Record<string, string[]>} */
    const byField = {}
    /** @type {SummaryError[]} */
    const unmapped = []

    error.fieldErrors.forEach(fe => {
      if (knownSet.has(fe.field)) {
        if (!byField[fe.field]) byField[fe.field] = []
        byField[fe.field].push(fe.message)
      } else {
        unmapped.push({ message: `${fe.field}: ${fe.message}`, traceId: error.traceId })
      }
    })

    return {
      getFieldErrors: (fieldName) => byField[fieldName] ?? [],
      summaryErrors: unmapped,
      traceId: error.traceId,
    }
  }, [error, knownFields])
}

/**
 * @param {{
 *   errors: SummaryError[],
 *   traceId?: string | null
 * }} props
 */
export function ErrorSummary({ errors, traceId }) {
  if (!errors || errors.length === 0) return null
  return (
    <div
      role="alert"
      aria-live="polite"
      data-component="error-summary"
      style={{
        background: 'var(--token-danger-subtle)',
        border: '1px solid var(--token-danger-default)',
        borderRadius: 'var(--token-radius-control)',
        padding: 'var(--token-space-3)',
        marginBottom: 'var(--token-space-4)',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
        color: 'var(--token-danger-emphasis)',
      }}
    >
      {errors.map((e, i) => (
        <p key={i} style={{ margin: '0 0 var(--token-space-1) 0' }}>{e.message}</p>
      ))}
      {traceId && (
        <p style={{ margin: '0', fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>
          Trace ID: <code>{traceId}</code>
        </p>
      )}
    </div>
  )
}
