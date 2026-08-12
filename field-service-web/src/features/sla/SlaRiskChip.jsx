/**
 * SlaRiskChip — SLA risk state chip for dispatch board rows, cards,
 * detail headers, and the alert centre.
 *
 * Risk state is communicated through icon + text label + token colour
 * so it is never colour-alone (WCAG 2.1 AA, BR-34).
 *
 * When `stale` is true the chip is styled as possibly out-of-date and
 * the aria-label includes an explicit "possibly out of date" suffix.
 *
 * @module features/sla/SlaRiskChip
 */

import React from 'react';

import styles from './SlaRiskChip.module.css';

/** @typedef {'healthy' | 'at-risk' | 'breached'} SlaRiskState */

const RISK_META = {
  healthy:   { icon: '✓', label: 'On track' },
  'at-risk': { icon: '⬥', label: 'At risk'  },
  breached:  { icon: '▲', label: 'Breached' },
};

/**
 * Formats an absolute number of minutes into a compact label.
 *
 * @param {number} absMinutes
 * @returns {string}
 */
function formatMinutes(absMinutes) {
  if (absMinutes < 60) return `${absMinutes}m`;
  const h = Math.floor(absMinutes / 60);
  const m = absMinutes % 60;
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

/**
 * @param {{
 *   riskState: SlaRiskState | string,
 *   minutesRemaining?: number | null,
 *   stale?: boolean,
 * }} props
 */
export function SlaRiskChip({ riskState, minutesRemaining = null, stale = false }) {
  const normalized = riskState?.toLowerCase().replace(/[\s_]/g, '-') ?? '';
  const meta = RISK_META[normalized] ?? { icon: '●', label: riskState ?? 'Unknown' };

  const isOverrun = minutesRemaining != null && minutesRemaining < 0;

  let timeLabel = null;
  if (minutesRemaining != null) {
    if (isOverrun) {
      timeLabel = `${formatMinutes(Math.abs(minutesRemaining))} overrun`;
    } else if (minutesRemaining === 0) {
      timeLabel = 'Due now';
    } else {
      timeLabel = `${formatMinutes(minutesRemaining)} remaining`;
    }
  }

  const ariaLabel = [
    meta.label,
    timeLabel,
    stale ? '(possibly out of date)' : null,
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <span
      className={[
        styles.chip,
        styles[`state-${normalized || 'unknown'}`],
        stale ? styles.stale : '',
      ]
        .filter(Boolean)
        .join(' ')}
      role="status"
      aria-label={ariaLabel}
      title={ariaLabel}
    >
      <span className={styles.icon} aria-hidden="true">{meta.icon}</span>
      <span className={styles.label}>{meta.label}</span>
      {timeLabel && <span className={styles.time}>{timeLabel}</span>}
      {stale && (
        <span className={styles.staleMarker} aria-hidden="true" title="Possibly out of date">
          ⊘
        </span>
      )}
    </span>
  );
}
