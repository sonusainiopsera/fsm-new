/**
 * @fileoverview KpiCard — muted label, tabular-figure value, delta chip, target attainment.
 * Degrades gracefully when delta, target or sparkline data are absent.
 */

/**
 * @typedef {{ ts: number, value: number }[]} SparklineSeries
 */

/**
 * @param {{
 *   label: string,
 *   value: string | number,
 *   delta?: number | null,
 *   deltaPeriodLabel?: string,
 *   target?: number | null,
 *   currentRaw?: number | null,
 *   sparkline?: SparklineSeries | null,
 *   unit?: string
 * }} props
 */
export function KpiCard({ label, value, delta, deltaPeriodLabel = 'vs prior period', target, currentRaw, sparkline, unit }) {
  const hasDelta = delta !== undefined && delta !== null
  const hasTarget = target !== undefined && target !== null && currentRaw !== undefined && currentRaw !== null
  const attainment = hasTarget ? Math.min(100, Math.round((currentRaw / target) * 100)) : null

  return (
    <article
      data-component="kpi-card"
      style={{
        background: 'var(--token-surface-card)',
        border: 'var(--token-elevation-border)',
        borderRadius: 'var(--token-radius-card)',
        padding: 'var(--token-space-4) var(--token-space-4) var(--token-space-6)',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-2)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      {/* 12–13 px muted label */}
      <span
        style={{
          fontSize: 'var(--token-fs-13)',
          color: 'var(--token-text-secondary)',
          fontWeight: 500,
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
        }}
      >
        {label}
      </span>

      {/* 30 px tabular-figure value */}
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--token-space-2)' }}>
        <span
          style={{
            fontSize: 'var(--token-fs-30)',
            fontVariantNumeric: 'var(--token-numeric)',
            color: 'var(--token-text-primary)',
            fontWeight: 700,
            lineHeight: 1.1,
            letterSpacing: 'var(--token-ls-tight)',
          }}
          aria-label={`${label}: ${value}${unit ? ' ' + unit : ''}`}
        >
          {value}
        </span>
        {unit && (
          <span style={{ fontSize: 'var(--token-fs-16)', color: 'var(--token-text-secondary)' }}>
            {unit}
          </span>
        )}
      </div>

      {/* Delta chip — omitted when delta is absent (AC3 edge case) */}
      {hasDelta && (
        <span
          data-delta-chip
          aria-label={`${delta >= 0 ? '+' : ''}${delta}% ${deltaPeriodLabel}`}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 'var(--token-space-1)',
            fontSize: 'var(--token-fs-12)',
            padding: 'var(--token-space-1) var(--token-space-2)',
            borderRadius: 'var(--token-radius-pill)',
            background: delta >= 0 ? 'var(--token-success-subtle)' : 'var(--token-danger-subtle)',
            color: delta >= 0 ? 'var(--token-success-emphasis)' : 'var(--token-danger-emphasis)',
            border: `1px solid ${delta >= 0 ? 'var(--token-success-default)' : 'var(--token-danger-default)'}`,
            width: 'fit-content',
            fontWeight: 500,
          }}
        >
          <span aria-hidden="true">{delta >= 0 ? '▲' : '▼'}</span>
          <span>{Math.abs(delta)}%</span>
          <span style={{ color: 'var(--token-text-secondary)', fontWeight: 400 }}>{deltaPeriodLabel}</span>
        </span>
      )}

      {/* Target attainment indicator */}
      {hasTarget && (
        <div style={{ marginTop: 'var(--token-space-1)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 'var(--token-space-1)' }}>
            <span style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>Target attainment</span>
            <span style={{ fontSize: 'var(--token-fs-12)', fontVariantNumeric: 'var(--token-numeric)', color: 'var(--token-text-primary)', fontWeight: 500 }}>
              {attainment}%
            </span>
          </div>
          <div
            role="progressbar"
            aria-valuenow={attainment}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label={`${label} target attainment: ${attainment}%`}
            style={{
              height: '4px',
              borderRadius: 'var(--token-radius-pill)',
              background: 'var(--token-neutral-200)',
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                height: '100%',
                width: `${attainment}%`,
                borderRadius: 'var(--token-radius-pill)',
                background: 'var(--token-neutral-600)',
                transition: 'width var(--token-duration-enter) var(--token-easing-standard)',
              }}
            />
          </div>
        </div>
      )}

      {/* Optional sparkline */}
      {sparkline && sparkline.length > 1 && (
        <SparklineChart series={sparkline} label={label} />
      )}
    </article>
  )
}

/**
 * @param {{ series: import('./KpiCard.jsx').SparklineSeries, label: string }} props
 */
function SparklineChart({ series, label }) {
  const values = series.map(p => p.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const W = 120
  const H = 32
  const pts = series.map((p, i) => {
    const x = (i / (series.length - 1)) * W
    const y = H - ((p.value - min) / range) * H
    return `${x},${y}`
  }).join(' ')

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      width={W}
      height={H}
      aria-label={`${label} trend sparkline`}
      role="img"
      style={{ overflow: 'visible', marginTop: 'var(--token-space-2)' }}
    >
      <polyline
        points={pts}
        fill="none"
        stroke="var(--token-accent-400)"
        strokeWidth="1.5"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  )
}
