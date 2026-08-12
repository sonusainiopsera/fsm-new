import React from 'react';
import { AccessibleSeriesTable } from './AccessibleSeriesTable.jsx';
import styles from './MetricChart.module.css';

const WIDTH  = 600;
const HEIGHT = 180;
const PAD_X  = 40;
const PAD_Y  = 16;

/**
 * Maps data values for one series to SVG coordinate points.
 *
 * @param {Array<Record<string, number | string>>} data
 * @param {string} seriesKey
 * @param {{ min: number, max: number }} yBounds
 * @returns {string}
 */
function toPolylinePoints(data, seriesKey, { min, max }) {
  const range = max - min || 1;
  const step  = data.length > 1 ? (WIDTH - PAD_X * 2) / (data.length - 1) : 0;
  const pts   = [];
  for (let i = 0; i < data.length; i++) {
    const v = data[i][seriesKey];
    if (v == null || v === '') {
      pts.push(null);
      continue;
    }
    const x = PAD_X + i * step;
    const y = PAD_Y + (HEIGHT - PAD_Y * 2) - ((Number(v) - min) / range) * (HEIGHT - PAD_Y * 2);
    pts.push(`${x.toFixed(1)},${y.toFixed(1)}`);
  }
  // Render as contiguous segments — gaps become separate polylines
  const segments = [];
  let current = [];
  for (const p of pts) {
    if (p === null) {
      if (current.length > 1) segments.push(current.join(' '));
      current = [];
    } else {
      current.push(p);
    }
  }
  if (current.length > 1) segments.push(current.join(' '));
  return segments;
}

/**
 * Derives y-axis bounds across all series.
 *
 * @param {Array<Record<string, number | string>>} data
 * @param {string[]} keys
 * @returns {{ min: number, max: number }}
 */
function yBounds(data, keys) {
  let min = Infinity;
  let max = -Infinity;
  for (const row of data) {
    for (const key of keys) {
      const v = row[key];
      if (v != null && v !== '') {
        const n = Number(v);
        if (!isNaN(n)) { min = Math.min(min, n); max = Math.max(max, n); }
      }
    }
  }
  if (!isFinite(min)) { min = 0; max = 1; }
  if (min === max) { min = min - 1; max = max + 1; }
  return { min, max };
}

/**
 * Y-axis tick labels (4 ticks).
 *
 * @param {{ min: number, max: number }} bounds
 * @returns {number[]}
 */
function yTicks({ min, max }) {
  const count = 4;
  const step  = (max - min) / (count - 1);
  return Array.from({ length: count }, (_, i) => min + step * i);
}

/**
 * X-axis labels — show every Nth label to avoid overcrowding.
 *
 * @param {Array<Record<string, string | number>>} data
 * @returns {{ i: number, label: string }[]}
 */
function xLabels(data) {
  const every = Math.max(1, Math.floor(data.length / 6));
  return data
    .map((row, i) => ({ i, label: String(row.period ?? row.date ?? row.label ?? i + 1) }))
    .filter((_, i) => i % every === 0 || i === data.length - 1);
}

const CHART_ID_COUNTER = { n: 0 };

/**
 * SVG-based multi-series line chart.
 *
 * No charting library dependency — drawn with polyline elements.
 * Colour-blind-safe design: accent series uses --color-accent-base,
 * additional series use neutral ramp tokens. Meaning is never conveyed
 * by colour alone — the first series is also rendered with a slightly
 * thicker stroke and each series has a legend label.
 *
 * @param {{
 *   trend: import('../api/useDashboardWidgets.js').TrendDto,
 *   headingId?: string,
 * }} props
 */
export function MetricChart({ trend, headingId }) {
  const tableId = React.useRef(`chart-table-${++CHART_ID_COUNTER.n}`).current;

  if (!trend || !trend.data || trend.data.length === 0 || !trend.series || trend.series.length === 0) {
    return null;
  }

  const { caption, series, data } = trend;
  const keys   = series.map((s) => s.key);
  const bounds = yBounds(data, keys);
  const ticks  = yTicks(bounds);
  const xlbls  = xLabels(data);
  const step   = data.length > 1 ? (WIDTH - PAD_X * 2) / (data.length - 1) : 0;

  const SERIES_COLORS = [
    'var(--color-accent-base)',
    'var(--color-neutral-400)',
    'var(--color-neutral-500)',
    'var(--color-neutral-600)',
  ];

  return (
    <figure className={styles.figure} aria-labelledby={headingId ?? undefined}>
      {/* Legend */}
      {series.length > 1 && (
        <div className={styles.legend} role="list">
          {series.map((s, i) => (
            <div key={s.key} className={styles.legendItem} role="listitem">
              <span
                className={styles.legendSwatch}
                style={{ background: SERIES_COLORS[i % SERIES_COLORS.length] }}
                aria-hidden="true"
              />
              <span className={styles.legendLabel}>{s.name}</span>
            </div>
          ))}
        </div>
      )}

      {/* SVG chart — linked to accessible table via aria-describedby */}
      <svg
        className={styles.svg}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={caption}
        aria-describedby={tableId}
        preserveAspectRatio="none"
      >
        {/* Y-axis grid lines + labels */}
        {ticks.map((t, i) => {
          const y = PAD_Y + (HEIGHT - PAD_Y * 2) - ((t - bounds.min) / (bounds.max - bounds.min)) * (HEIGHT - PAD_Y * 2);
          return (
            <g key={i}>
              <line x1={PAD_X} y1={y.toFixed(1)} x2={WIDTH - PAD_X} y2={y.toFixed(1)} className={styles.gridLine} />
              <text x={PAD_X - 4} y={y.toFixed(1)} className={styles.yLabel} textAnchor="end" dominantBaseline="middle">
                {Number.isInteger(t) ? t : t.toFixed(1)}
              </text>
            </g>
          );
        })}

        {/* X-axis labels */}
        {xlbls.map(({ i, label }) => {
          const x = PAD_X + i * step;
          return (
            <text key={i} x={x.toFixed(1)} y={HEIGHT - 2} className={styles.xLabel} textAnchor="middle">
              {label}
            </text>
          );
        })}

        {/* Series polylines */}
        {series.map((s, si) => {
          const segments = toPolylinePoints(data, s.key, bounds);
          const color    = SERIES_COLORS[si % SERIES_COLORS.length];
          const width    = si === 0 ? 2 : 1.5;
          return segments.map((pts, pi) => (
            <polyline
              key={`${s.key}-${pi}`}
              points={pts}
              fill="none"
              stroke={color}
              strokeWidth={width}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          ));
        })}
      </svg>

      {/* Accessible tabular equivalent */}
      <AccessibleSeriesTable id={tableId} caption={caption} series={series} data={data} />
    </figure>
  );
}
