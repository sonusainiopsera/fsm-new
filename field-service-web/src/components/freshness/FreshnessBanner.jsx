/**
 * FreshnessBanner — declares the observation time of polled status data and
 * surfaces a visible degraded or not-connected state.
 *
 * Rules (from WO-174 AC-5):
 * - Always shows the last-updated timestamp when data is available.
 * - Shows a "Degraded" banner when freshness.degraded is true.
 * - Shows a "Not connected" banner when the last fetch failed (isError=true).
 * - Never presents stale data as current — the observation time is always visible.
 *
 * Accessibility: status changes are announced via an aria-live="polite" region
 * so screen-reader users hear updates without losing focus.
 */

import React from 'react';
import styles from './FreshnessBanner.module.css';

/**
 * @param {{
 *   observedAt?: string | null,
 *   staleAfterSeconds?: number,
 *   degraded?: boolean,
 *   isError?: boolean,
 *   onRetry?: () => void,
 *   className?: string,
 * }} props
 */
export function FreshnessBanner({
  observedAt,
  staleAfterSeconds = 60,
  degraded = false,
  isError = false,
  onRetry,
  className = '',
}) {
  const formattedTime = observedAt
    ? new Date(observedAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    : null;

  if (isError) {
    return (
      <div
        className={[styles.banner, styles.notConnected, className].filter(Boolean).join(' ')}
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >
        <span className={styles.icon} aria-hidden="true">⚠</span>
        <span className={styles.message}>
          Not connected — showing last known information
          {formattedTime ? ` as of ${formattedTime}` : ''}.
        </span>
        {onRetry && (
          <button
            type="button"
            className={styles.retryButton}
            onClick={onRetry}
            aria-label="Retry fetching latest status"
          >
            Retry
          </button>
        )}
      </div>
    );
  }

  if (degraded) {
    return (
      <div
        className={[styles.banner, styles.degraded, className].filter(Boolean).join(' ')}
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >
        <span className={styles.icon} aria-hidden="true">⚠</span>
        <span className={styles.message}>
          Status may be slightly delayed — information as of {formattedTime ?? 'unknown'}.
        </span>
      </div>
    );
  }

  if (!formattedTime) return null;

  return (
    <div
      className={[styles.banner, styles.fresh, className].filter(Boolean).join(' ')}
      role="status"
      aria-live="polite"
      aria-atomic="true"
      aria-label={`Status last updated at ${formattedTime}. Refreshes every ${staleAfterSeconds} seconds.`}
    >
      <span className={styles.icon} aria-hidden="true">↻</span>
      <span className={styles.message}>
        Updated at {formattedTime} · refreshes every {staleAfterSeconds}s
      </span>
    </div>
  );
}
