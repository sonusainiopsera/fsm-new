import React from 'react';

import { useDensity } from '../../density/DensityContext.js';

import styles from './KpiCard.module.css';

/**
 * @typedef {{
 *   label: string,
 *   value: string | number,
 *   delta?: number,
 *   deltaLabel?: string,
 *   target?: number,
 *   current?: number,
 *   sparklineData?: number[],
 * }} KpiCardProps
 */

/**
 * Miniature sparkline rendered as inline SVG polyline.
 * @param {{ data: number[], width?: number, height?: number }} props
 */
function Sparkline({ data, width = 120, height = 32 }) {
  if (!data || data.length < 2) return null;
  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 1;
  const step = width / (data.length - 1);
  const points = data
    .map((v, i) => `${i * step},${height - ((v - min) / range) * height}`)
    .join(' ');

  return (
    <svg
      className={styles.sparkline}
      viewBox={`0 0 ${width} ${height}`}
      aria-hidden="true"
      preserveAspectRatio="none"
    >
      <polyline
        points={points}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/**
 * KPI card with label, tabular-figure value, optional delta, target attainment
 * and sparkline. Degrades gracefully when optional data is absent.
 *
 * @param {KpiCardProps} props
 */
export function KpiCard({ label, value, delta, deltaLabel, target, current, sparklineData }) {
  const { density } = useDensity();

  let deltaClass = styles['delta-neutral'];
  let deltaPrefix = '';
  if (delta != null) {
    if (delta > 0) { deltaClass = styles['delta-positive']; deltaPrefix = '+'; }
    else if (delta < 0) { deltaClass = styles['delta-negative']; }
  }

  let fillPct = null;
  let fillClass = styles.fill;
  if (target != null && current != null && target > 0) {
    fillPct = Math.min(100, Math.round((current / target) * 100));
    if (fillPct >= 100) fillClass = `${styles.fill} ${styles['fill-exceeded']}`;
    else if (fillPct < 70) fillClass = `${styles.fill} ${styles['fill-warning']}`;
  }

  return (
    <article className={[styles.card, density === 'compact' ? styles.compact : ''].filter(Boolean).join(' ')}>
      <span className={styles.label}>{label}</span>

      <div className={styles.valueRow}>
        <span className={`${styles.value} numeric`}>{value}</span>
        {delta != null && (
          <span className={`${styles.delta} ${deltaClass}`} aria-label={`${deltaPrefix}${delta}${deltaLabel ? ' ' + deltaLabel : ''} vs prior period`}>
            {deltaPrefix}{delta}{deltaLabel ? ` ${deltaLabel}` : ''}
          </span>
        )}
      </div>

      {fillPct != null && (
        <div className={styles.targetRow}>
          <span className={styles.targetLabel}>{fillPct}% of target {target}</span>
          <div className={styles.track} role="progressbar" aria-valuenow={fillPct} aria-valuemin={0} aria-valuemax={100}>
            <div className={fillClass} style={{ width: `${fillPct}%` }} />
          </div>
        </div>
      )}

      {sparklineData && <Sparkline data={sparklineData} />}
    </article>
  );
}
