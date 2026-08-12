/**
 * @fileoverview DataAgeBadge — formats a widget's dataAsOf timestamp as a
 * human-readable relative age and warns when data is stale (stalenessSeconds > 60).
 */

/**
 * Formats elapsed seconds into a compact relative-time string.
 *
 * @param {number} seconds
 * @returns {string}
 */
function formatAge(seconds) {
  if (seconds < 60) return `${seconds}s ago`
  const mins = Math.floor(seconds / 60)
  if (mins < 60) return `${mins} min ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.floor(hrs / 24)
  return `${days}d ago`
}

/**
 * Calculates stalenessSeconds from a dataAsOf ISO string and the current time.
 * Used when the server does not supply stalenessSeconds directly.
 *
 * @param {string} dataAsOf  ISO 8601 date-time string
 * @param {number} [nowMs]   Override for the current time (for testing)
 * @returns {number}
 */
export function calcStaleness(dataAsOf, nowMs = Date.now()) {
  const then = new Date(dataAsOf).getTime()
  if (Number.isNaN(then)) return 0
  return Math.max(0, Math.round((nowMs - then) / 1000))
}

const STALE_THRESHOLD_S = 60

/**
 * @param {{
 *   dataAsOf?: string | null,
 *   stalenessSeconds?: number | null,
 *   degraded?: boolean
 * }} props
 */
export function DataAgeBadge({ dataAsOf, stalenessSeconds, degraded = false }) {
  if (!dataAsOf && (stalenessSeconds == null)) return null

  const seconds = stalenessSeconds ?? calcStaleness(dataAsOf ?? '')
  const isStale = seconds > STALE_THRESHOLD_S
  const label = formatAge(seconds)

  const color = degraded || isStale
    ? 'var(--token-warning-emphasis)'
    : 'var(--token-text-secondary)'

  const bg = degraded || isStale
    ? 'var(--token-warning-subtle)'
    : 'transparent'

  const border = degraded || isStale
    ? '1px solid var(--token-warning-default)'
    : 'none'

  return (
    <span
      data-component="data-age-badge"
      aria-label={`Data as of ${label}${isStale ? ', stale' : ''}`}
      title={dataAsOf ? `Data timestamp: ${dataAsOf}` : undefined}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 'var(--token-space-1)',
        fontSize: 'var(--token-fs-12)',
        color,
        background: bg,
        border,
        borderRadius: 'var(--token-radius-pill)',
        padding: (degraded || isStale) ? 'var(--token-space-1) var(--token-space-2)' : '0',
        fontFamily: 'var(--token-family-base)',
        fontVariantNumeric: 'var(--token-numeric)',
        lineHeight: 1.4,
      }}
    >
      {(degraded || isStale) && (
        <span aria-hidden="true" style={{ fontSize: 'var(--token-fs-11)' }}>⚠</span>
      )}
      {label}
    </span>
  )
}
