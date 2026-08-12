/**
 * @fileoverview SlaRiskChip — shared SLA risk indicator chip.
 *
 * Encodes risk state via icon + text label + colour so meaning survives
 * greyscale and colour-blindness (BR-34, not colour-only).
 *
 * States:
 *  - healthy:  no SLA risk
 *  - at_risk:  approaching deadline
 *  - breached: SLA deadline exceeded
 *
 * When `stale` is true, an out-of-date affordance is applied so dispatchers
 * never mistake stale data for live. (WO-147 constraint: never display stale
 * risk as current.)
 */

/**
 * Formats minutes remaining or overrun into a human-readable string.
 * Zero or negative → overrun display.
 *
 * @param {number | null | undefined} minutes
 * @returns {string}
 */
export function formatSlaMinutes(minutes) {
  if (minutes == null) return ''
  if (minutes <= 0) {
    const overrun = Math.abs(Math.floor(minutes))
    if (overrun < 60) return `${overrun}m overrun`
    const h = Math.floor(overrun / 60)
    const m = overrun % 60
    return m > 0 ? `${h}h ${m}m overrun` : `${h}h overrun`
  }
  if (minutes < 60) return `${Math.floor(minutes)}m`
  const h = Math.floor(minutes / 60)
  const m = Math.floor(minutes % 60)
  return m > 0 ? `${h}h ${m}m` : `${h}h`
}

/** @type {Record<string, { label: string, icon: string, bg: string, color: string, border: string }>} */
const RISK_META = {
  healthy: {
    label: 'Healthy',
    icon: '✓',
    bg: 'var(--token-success-subtle)',
    color: 'var(--token-success-emphasis)',
    border: 'var(--token-success-default)',
  },
  at_risk: {
    label: 'At risk',
    icon: '⚠',
    bg: 'var(--token-warning-subtle)',
    color: 'var(--token-warning-emphasis)',
    border: 'var(--token-warning-default)',
  },
  breached: {
    label: 'Breached',
    icon: '✕',
    bg: 'var(--token-danger-subtle)',
    color: 'var(--token-danger-emphasis)',
    border: 'var(--token-danger-default)',
  },
}

/**
 * @param {{
 *   riskLevel: 'healthy' | 'at_risk' | 'breached',
 *   minutesRemaining?: number | null,
 *   stale?: boolean,
 *   size?: 'sm' | 'md',
 *   onAttribute?: () => void,
 * }} props
 */
export function SlaRiskChip({ riskLevel, minutesRemaining, stale = false, size = 'md', onAttribute }) {
  const meta = RISK_META[riskLevel] ?? RISK_META.healthy
  const timeLabel = formatSlaMinutes(minutesRemaining)

  const staleStyle = stale
    ? { opacity: 0.65, outline: '1.5px dashed var(--token-border-default)' }
    : {}

  const isSmall = size === 'sm'
  const padding = isSmall
    ? 'var(--token-space-1) var(--token-space-2)'
    : 'calc(var(--token-space-1) * 1.5) var(--token-space-3)'
  const fontSize = isSmall ? 'var(--token-fs-12)' : 'var(--token-fs-13)'

  const label = timeLabel
    ? `${meta.label}: ${timeLabel}${stale ? ' (data may be out of date)' : ''}`
    : `${meta.label}${stale ? ' (data may be out of date)' : ''}`

  return (
    <span
      role="status"
      aria-label={label}
      data-sla-risk={riskLevel}
      data-stale={stale ? 'true' : undefined}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 'var(--token-space-1)',
        padding,
        fontSize,
        fontFamily: 'var(--token-family-base)',
        borderRadius: 'var(--token-radius-pill)',
        background: meta.bg,
        color: meta.color,
        border: `1px solid ${meta.border}`,
        whiteSpace: 'nowrap',
        fontWeight: 500,
        ...staleStyle,
      }}
    >
      <span aria-hidden="true" style={{ fontSize: '0.85em', lineHeight: 1 }}>{meta.icon}</span>
      <span>{meta.label}</span>
      {timeLabel && (
        <span style={{ fontVariantNumeric: 'var(--token-numeric)', marginLeft: 'var(--token-space-1)' }}>
          {timeLabel}
        </span>
      )}
      {stale && (
        <span
          aria-hidden="true"
          title="Data may be out of date"
          style={{ fontSize: '0.8em', opacity: 0.7, marginLeft: 'var(--token-space-1)' }}
        >
          ⏷
        </span>
      )}
      {riskLevel === 'breached' && onAttribute && (
        <button
          type="button"
          aria-label="Record breach reason"
          onClick={(e) => { e.stopPropagation(); onAttribute() }}
          style={{
            marginLeft: 'var(--token-space-1)',
            background: 'transparent',
            border: 'none',
            cursor: 'pointer',
            color: meta.color,
            fontSize: '0.85em',
            padding: '0 var(--token-space-1)',
            minWidth: 44,
            minHeight: 44,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          ✎
        </button>
      )}
    </span>
  )
}
