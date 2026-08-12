/**
 * LiveStreamIndicator — top-bar SSE connection health indicator.
 *
 * Three states: live (green dot), reconnecting (pulsing grey), stale (⊘ amber).
 * All states have an accessible text label and a tooltip.
 * When stale a manual refresh button is offered so operators are never left
 * silently staring at stale data.
 *
 * @module app/TopBar/LiveStreamIndicator
 */

import React from 'react';

import styles from './LiveStreamIndicator.module.css';

/** @typedef {import('../../features/sla/useSlaAlertStream.js').StreamStatus} StreamStatus */

/** @type {Record<string, { icon: string, label: string, tooltip: string }>} */
const STATUS_META = {
  live:         { icon: '●', label: 'Live',         tooltip: 'Real-time SLA updates active' },
  reconnecting: { icon: '○', label: 'Reconnecting', tooltip: 'Reconnecting to live SLA updates…' },
  stale:        { icon: '⊘', label: 'Stale',        tooltip: 'Live updates unavailable — data may be out of date' },
};

/**
 * @param {{
 *   status: StreamStatus,
 *   onRefresh?: () => void,
 * }} props
 */
export function LiveStreamIndicator({ status, onRefresh }) {
  const meta = STATUS_META[status] ?? STATUS_META.stale;
  const isStale = status === 'stale';

  return (
    <div
      className={[
        styles.indicator,
        styles[`status-${status ?? 'stale'}`],
      ].join(' ')}
      role="status"
      aria-live="polite"
      aria-label={meta.tooltip}
      title={meta.tooltip}
    >
      <span className={styles.dot} aria-hidden="true">{meta.icon}</span>
      <span className={styles.label}>{meta.label}</span>

      {isStale && onRefresh && (
        <button
          type="button"
          className={styles.refreshBtn}
          onClick={onRefresh}
          aria-label="Refresh live SLA feed"
          title="Refresh live SLA feed"
        >
          ↻
        </button>
      )}
    </div>
  );
}
