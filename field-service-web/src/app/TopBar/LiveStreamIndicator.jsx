/**
 * @fileoverview LiveStreamIndicator — top-bar indicator for SSE stream health.
 *
 * Conveys three states via icon + text + accessible label (not colour-only, BR-34):
 *   live:         stream is connected and receiving heartbeats
 *   reconnecting: connection dropped, actively attempting reconnect
 *   stale:        no heartbeat received within staleness threshold
 *
 * When stale or reconnecting, a manual refresh action is offered.
 * WCAG 2.1 AA: role="status" with aria-live="polite" for dynamic updates.
 */

/**
 * @param {{
 *   status: 'live' | 'reconnecting' | 'stale',
 *   onRefresh?: () => void,
 * }} props
 */
export function LiveStreamIndicator({ status, onRefresh }) {
  const config = {
    live: {
      icon: '●',
      label: 'Live stream: connected',
      shortLabel: 'Live',
      color: 'var(--token-success-emphasis)',
    },
    reconnecting: {
      icon: '◌',
      label: 'Live stream: reconnecting',
      shortLabel: 'Reconnecting',
      color: 'var(--token-warning-emphasis)',
    },
    stale: {
      icon: '◎',
      label: 'Live stream: data may be out of date',
      shortLabel: 'Stale',
      color: 'var(--token-danger-emphasis)',
    },
  }

  const c = config[status] ?? config.reconnecting

  return (
    <div
      role="status"
      aria-live="polite"
      aria-label={c.label}
      title={c.label}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 'var(--token-space-1)',
        fontSize: 'var(--token-fs-12)',
        color: c.color,
        padding: 'var(--token-space-1) var(--token-space-2)',
        borderRadius: 'var(--token-radius-pill)',
        background:
          status === 'live'
            ? 'var(--token-success-subtle)'
            : status === 'stale'
            ? 'var(--token-danger-subtle)'
            : 'var(--token-warning-subtle)',
        border: `1px solid ${c.color}`,
        minHeight: 28,
      }}
    >
      <span aria-hidden="true" style={{ fontSize: '0.7em', lineHeight: 1 }}>{c.icon}</span>
      <span style={{ fontWeight: 500 }}>{c.shortLabel}</span>
      {(status === 'stale' || status === 'reconnecting') && onRefresh && (
        <button
          type="button"
          aria-label="Refresh live stream"
          onClick={onRefresh}
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            color: c.color,
            padding: '0 var(--token-space-1)',
            fontSize: '0.9em',
            minHeight: 44,
            minWidth: 44,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
          title="Reconnect stream"
        >
          ↺
        </button>
      )}
    </div>
  )
}
