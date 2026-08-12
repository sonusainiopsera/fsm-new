/**
 * @fileoverview FreshnessBanner — displays data freshness, degraded and not-connected states.
 *
 * Rendered as an ARIA live region so screen readers announce state changes.
 * Never shows the word "stale" to the customer; uses plain-language alternatives.
 */

/**
 * @param {{
 *   freshness?: { observedAt: string, staleAfterSeconds: number, degraded: boolean } | null,
 *   isError?: boolean,
 *   onRetry?: () => void
 * }} props
 */
export function FreshnessBanner({ freshness, isError = false, onRetry }) {
  if (isError) {
    return (
      <div
        role="alert"
        aria-live="assertive"
        aria-atomic="true"
        data-testid="not-connected-banner"
        style={{
          padding: 'var(--token-space-4) var(--token-space-6)',
          background: 'var(--token-warning-subtle)',
          border: '1px solid var(--token-warning-default)',
          borderRadius: 'var(--token-radius-md)',
          display: 'flex',
          alignItems: 'center',
          gap: 'var(--token-space-4)',
          fontSize: 'var(--token-fs-15)',
          color: 'var(--token-text-primary)',
        }}
      >
        <span aria-hidden="true" style={{ fontSize: 'var(--token-fs-18)' }}>⚠</span>
        <span style={{ flex: 1 }}>
          Unable to connect. The information shown may not be current.
        </span>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            style={{
              background: 'none',
              border: '1px solid var(--token-warning-default)',
              borderRadius: 'var(--token-radius-sm)',
              padding: 'var(--token-space-2) var(--token-space-4)',
              cursor: 'pointer',
              fontSize: 'var(--token-fs-14)',
              color: 'var(--token-text-primary)',
              minHeight: '44px',
            }}
          >
            Retry
          </button>
        )}
      </div>
    )
  }

  if (freshness?.degraded) {
    return (
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="degraded-banner"
        style={{
          padding: 'var(--token-space-4) var(--token-space-6)',
          background: 'var(--token-warning-subtle)',
          border: '1px solid var(--token-warning-default)',
          borderRadius: 'var(--token-radius-md)',
          fontSize: 'var(--token-fs-15)',
          color: 'var(--token-text-primary)',
        }}
      >
        <span aria-hidden="true" style={{ marginRight: 'var(--token-space-2)' }}>ℹ</span>
        Some information may be incomplete. Last checked{' '}
        <time dateTime={freshness.observedAt}>
          {formatTime(freshness.observedAt)}
        </time>
        .
      </div>
    )
  }

  if (freshness?.observedAt) {
    return (
      <p
        aria-live="polite"
        data-testid="freshness-indicator"
        style={{
          fontSize: 'var(--token-fs-13)',
          color: 'var(--token-text-secondary)',
          margin: 0,
        }}
      >
        Last updated{' '}
        <time dateTime={freshness.observedAt}>
          {formatTime(freshness.observedAt)}
        </time>
        . Refreshes automatically every minute.
      </p>
    )
  }

  return null
}

/**
 * Formats an ISO timestamp as a short time string.
 * @param {string} iso
 * @returns {string}
 */
function formatTime(iso) {
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}
