/**
 * @typedef {'empty' | 'loading' | 'degraded' | 'permission-denied' | 'error'} DisplayState
 */

/**
 * @param {{
 *   state: DisplayState,
 *   message: string,
 *   traceId?: string,
 *   stalenessInfo?: { what: string, age: string },
 * }} props
 */
export function StateDisplay({ state, message, traceId, stalenessInfo }) {
  const config = stateConfig[state]

  return (
    <div
      role={state === 'error' ? 'alert' : 'status'}
      aria-live={state === 'loading' ? 'polite' : undefined}
      aria-busy={state === 'loading'}
      style={{
        ...styles.container,
        borderColor: config.borderColor,
        backgroundColor: config.backgroundColor,
      }}
    >
      <span aria-hidden="true" style={styles.icon}>
        {config.icon}
      </span>
      <div style={styles.content}>
        <p style={styles.message}>{message}</p>

        {state === 'degraded' && stalenessInfo && (
          <p style={styles.detail}>
            <strong>{stalenessInfo.what}</strong> last updated {stalenessInfo.age} ago. Some
            information may be out of date.
          </p>
        )}

        {state === 'error' && traceId && (
          <p style={styles.traceId}>
            <span style={styles.traceLabel}>Trace ID: </span>
            <code style={styles.traceCode}>{traceId}</code>
          </p>
        )}
      </div>
    </div>
  )
}

/**
 * @type {Record<DisplayState, { icon: string, borderColor: string, backgroundColor: string }>}
 */
const stateConfig = {
  empty: {
    icon: '📭',
    borderColor: 'var(--color-border)',
    backgroundColor: 'var(--color-bg-subtle)',
  },
  loading: {
    icon: '⏳',
    borderColor: 'var(--color-border)',
    backgroundColor: 'var(--color-bg-subtle)',
  },
  degraded: {
    icon: '⚠️',
    borderColor: 'var(--color-warning)',
    backgroundColor: 'color-mix(in srgb, var(--color-warning) 8%, var(--color-bg-base))',
  },
  'permission-denied': {
    icon: '🔒',
    borderColor: 'var(--color-border)',
    backgroundColor: 'var(--color-bg-subtle)',
  },
  error: {
    icon: '⚠️',
    borderColor: 'var(--color-error)',
    backgroundColor: 'color-mix(in srgb, var(--color-error) 8%, var(--color-bg-base))',
  },
}

/** @type {Record<string, React.CSSProperties>} */
const styles = {
  container: {
    display: 'flex',
    gap: 'var(--space-4)',
    padding: 'var(--space-6)',
    borderRadius: 'var(--radius-lg)',
    border: '1px solid',
    alignItems: 'flex-start',
  },
  icon: {
    fontSize: 'var(--font-size-xl)',
    flexShrink: 0,
    lineHeight: 1.4,
  },
  content: {
    display: 'flex',
    flexDirection: 'column',
    gap: 'var(--space-2)',
    flex: 1,
  },
  message: {
    margin: 0,
    fontSize: 'var(--font-size-base)',
    fontWeight: 'var(--font-weight-medium)',
    color: 'var(--color-text-primary)',
    lineHeight: 1.5,
  },
  detail: {
    margin: 0,
    fontSize: 'var(--font-size-sm)',
    color: 'var(--color-text-secondary)',
    lineHeight: 1.5,
  },
  traceId: {
    margin: 0,
    fontSize: 'var(--font-size-xs)',
    color: 'var(--color-text-secondary)',
    lineHeight: 1.5,
  },
  traceLabel: {
    fontWeight: 'var(--font-weight-medium)',
  },
  traceCode: {
    fontFamily: 'monospace',
    backgroundColor: 'var(--color-bg-muted)',
    borderRadius: 'var(--radius-sm)',
    paddingInline: 'var(--space-1)',
    paddingBlock: '0.125rem',
    fontSize: 'var(--font-size-xs)',
    color: 'var(--color-text-secondary)',
  },
}
