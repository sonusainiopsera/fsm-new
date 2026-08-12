/**
 * @fileoverview MetricChart — SVG line chart for KPI trend data.
 *
 * Renders an accessible SVG polyline with gaps for missing/sparse buckets,
 * plus an AccessibleSeriesTable linked via aria-describedby for keyboard/SR users.
 *
 * No Recharts dependency — uses plain SVG, consistent with KpiCard's SparklineChart.
 */

/**
 * @typedef {{ ts: number, value: number }} TrendPoint
 */

const W = 240
const H = 80
const PAD_X = 4
const PAD_Y = 4

/**
 * Converts trend points to SVG coordinate pairs.  Returns an array of path
 * segment strings; consecutive non-null points are joined with L, gaps become M.
 *
 * @param {TrendPoint[]} points
 * @returns {{ pts: string, hasMissingBuckets: boolean }}
 */
function buildPath(points) {
  if (!points || points.length < 2) return { pts: '', hasMissingBuckets: false }

  const values = points.map(p => p.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const innerW = W - PAD_X * 2
  const innerH = H - PAD_Y * 2

  let hasMissingBuckets = false
  const segments = []
  let isFirst = true

  for (let i = 0; i < points.length; i++) {
    const p = points[i]
    if (p.value == null) {
      hasMissingBuckets = true
      isFirst = true
      continue
    }
    const x = PAD_X + (i / (points.length - 1)) * innerW
    const y = PAD_Y + innerH - ((p.value - min) / range) * innerH
    if (isFirst) {
      segments.push(`M${x.toFixed(1)},${y.toFixed(1)}`)
      isFirst = false
    } else {
      segments.push(`L${x.toFixed(1)},${y.toFixed(1)}`)
    }
  }

  return { pts: segments.join(' '), hasMissingBuckets }
}

/**
 * Formats a Unix epoch second timestamp to a locale date string.
 *
 * @param {number} ts  Unix epoch seconds
 * @returns {string}
 */
function fmtDate(ts) {
  try {
    return new Date(ts * 1000).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  } catch {
    return String(ts)
  }
}

/**
 * Renders the series as a keyboard-reachable table for screen-reader access.
 *
 * @param {{ id: string, label: string, points: TrendPoint[], unit?: string }} props
 */
export function AccessibleSeriesTable({ id, label, points, unit }) {
  return (
    <table
      id={id}
      style={{
        position: 'absolute',
        width: '1px',
        height: '1px',
        overflow: 'hidden',
        clip: 'rect(0,0,0,0)',
        whiteSpace: 'nowrap',
        border: 0,
      }}
    >
      <caption className="sr-only">{label} trend data</caption>
      <thead>
        <tr>
          <th scope="col">Date</th>
          <th scope="col">Value{unit ? ` (${unit})` : ''}</th>
        </tr>
      </thead>
      <tbody>
        {(points ?? []).map((p) => (
          <tr key={p.ts}>
            <td>{fmtDate(p.ts)}</td>
            <td>{p.value ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/**
 * @param {{
 *   id?: string,
 *   label: string,
 *   points: TrendPoint[],
 *   unit?: string,
 *   width?: number,
 *   height?: number
 * }} props
 */
export function MetricChart({ id, label, points, unit, width = W, height = H }) {
  const tableId = id ? `${id}-table` : `metric-chart-table-${label.replace(/\s+/g, '-').toLowerCase()}`

  if (!points || points.length < 2) {
    return (
      <div
        style={{
          width,
          height,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: 'var(--token-fs-12)',
          color: 'var(--token-text-secondary)',
          fontStyle: 'italic',
        }}
        aria-label={`${label} — no trend data available`}
      >
        No trend data
      </div>
    )
  }

  const { pts, hasMissingBuckets } = buildPath(points)

  return (
    <div style={{ position: 'relative' }}>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        width={width}
        height={height}
        role="img"
        aria-label={`${label} trend chart`}
        aria-describedby={tableId}
        style={{ overflow: 'visible', display: 'block' }}
      >
        {/* Baseline grid line */}
        <line
          x1={PAD_X}
          y1={H - PAD_Y}
          x2={W - PAD_X}
          y2={H - PAD_Y}
          stroke="var(--token-neutral-200)"
          strokeWidth="1"
        />

        {pts && (
          <path
            d={pts}
            fill="none"
            stroke="var(--token-accent-400)"
            strokeWidth="2"
            strokeLinejoin="round"
            strokeLinecap="round"
            strokeDasharray={hasMissingBuckets ? '4 3' : undefined}
          />
        )}

        {/* End-point dot */}
        {points[points.length - 1]?.value != null && (() => {
          const last = points[points.length - 1]
          const values = points.filter(p => p.value != null).map(p => p.value)
          const min = Math.min(...values)
          const max = Math.max(...values)
          const range = max - min || 1
          const innerW = W - PAD_X * 2
          const innerH = H - PAD_Y * 2
          const lastIdx = points.length - 1
          const x = PAD_X + (lastIdx / (points.length - 1)) * innerW
          const y = PAD_Y + innerH - ((last.value - min) / range) * innerH
          return (
            <circle
              cx={x}
              cy={y}
              r={3}
              fill="var(--token-accent-400)"
              aria-hidden="true"
            />
          )
        })()}
      </svg>

      {/* Keyboard-reachable accessible table (visually hidden) */}
      <AccessibleSeriesTable id={tableId} label={label} points={points} unit={unit} />
    </div>
  )
}
