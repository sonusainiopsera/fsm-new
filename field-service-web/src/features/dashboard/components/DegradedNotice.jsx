import React from 'react';
import styles from './DegradedNotice.module.css';

/**
 * Dashboard-level notice shown when all (or many) widgets are degraded.
 *
 * Does not replace per-widget degraded state — each card still shows its
 * own stale label. This is an additional top-of-page advisory.
 *
 * @param {{
 *   generatedAt?: string | null,
 * }} props
 */
export function DegradedNotice({ generatedAt }) {
  const absTime = generatedAt ? new Date(generatedAt).toLocaleString() : null;

  return (
    <div className={styles.notice} role="status" aria-live="polite">
      <span className={styles.icon} aria-hidden="true">⚠</span>
      <div className={styles.text}>
        <p className={styles.title}>Live data temporarily unavailable</p>
        <p className={styles.desc}>
          Displaying last known values.
          {absTime && ` Data as of ${absTime}.`}
          {' '}Metrics will update automatically when the service recovers.
        </p>
      </div>
    </div>
  );
}
