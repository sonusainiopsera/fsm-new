/**
 * @fileoverview GenericErrorAlert — aria-live error region for sign-in failures.
 *
 * Renders the API-supplied message verbatim for 401 (AC-6: no enumeration).
 * For 429 honours Retry-After (AC-7). For 503/network shows a distinct retry
 * message (AC-7). All other statuses render the API message as-is.
 *
 * The region uses role="alert" + aria-live="assertive" so assistive technology
 * announces errors immediately upon appearance (AC-9).
 *
 * @param {{
 *   error: {
 *     status: number,
 *     message: string,
 *     retryAfterSeconds?: number | null
 *   } | null,
 *   onDismiss?: () => void
 * }} props
 */
export function GenericErrorAlert({ error, onDismiss }) {
  if (!error) return null

  let message = error.message

  // AC-7: 429 — include Retry-After if present
  if (error.status === 429) {
    if (error.retryAfterSeconds && error.retryAfterSeconds > 0) {
      message = `${message} Retry in ${error.retryAfterSeconds} second${error.retryAfterSeconds === 1 ? '' : 's'}.`
    }
  }

  // AC-7: 503 or network failure (status 0) — distinct retry message
  if (error.status === 503 || error.status === 0) {
    message = error.message || 'The service is temporarily unavailable. Please try again shortly.'
  }

  return (
    <div
      data-testid="generic-error-alert"
      role="alert"
      aria-live="assertive"
      aria-atomic="true"
      style={{
        background: 'var(--token-danger-subtle)',
        border: '1px solid var(--token-danger-default)',
        borderRadius: 'var(--token-radius-control)',
        padding: 'var(--token-space-3)',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
        color: 'var(--token-danger-emphasis)',
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: 'var(--token-space-2)',
      }}
    >
      <span>{message}</span>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss error"
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            color: 'var(--token-danger-emphasis)',
            fontFamily: 'var(--token-family-base)',
            fontSize: 'var(--token-fs-14)',
            padding: 'var(--token-space-1)',
            minHeight: '44px',
            minWidth: '44px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: 'var(--token-radius-control)',
          }}
        >
          ✕
        </button>
      )}
    </div>
  )
}
