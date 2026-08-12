/**
 * StatusTimeline — renders an ordered list of customer-visible milestones.
 *
 * Rules (WO-174 AC-6):
 * - Labels come from the API payload only — no client-invented copy.
 * - No internal state enums, dispatch scores, coordinates, or PII.
 * - Respects prefers-reduced-motion for transition animations.
 * - Keyboard accessible: list items are not interactive but the component
 *   is announced as a region so screen readers can navigate to it.
 */

import React from 'react';
import styles from './StatusTimeline.module.css';

/**
 * @typedef {{ at: string, label: string }} Milestone
 */

/**
 * @param {{
 *   milestones: Milestone[],
 *   currentStatusLabel: string,
 *   className?: string,
 * }} props
 */
export function StatusTimeline({ milestones = [], currentStatusLabel, className = '' }) {
  if (milestones.length === 0 && !currentStatusLabel) return null;

  return (
    <section
      className={[styles.timeline, className].filter(Boolean).join(' ')}
      aria-label="Request timeline"
    >
      <h3 className={styles.heading}>Timeline</h3>
      <ol className={styles.list} aria-label="Status milestones">
        {milestones.map((m, i) => (
          <li key={`${m.at}-${i}`} className={styles.item}>
            <span className={styles.dot} aria-hidden="true" />
            <span className={styles.label}>{m.label}</span>
            <time
              className={styles.time}
              dateTime={m.at}
              aria-label={`at ${new Date(m.at).toLocaleString()}`}
            >
              {new Date(m.at).toLocaleString(undefined, {
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </time>
          </li>
        ))}

        {/* Current status as the last entry (no timestamp — it's now) */}
        {currentStatusLabel && (
          <li className={[styles.item, styles.current].join(' ')}>
            <span className={styles.dot} aria-hidden="true" />
            <span className={styles.label}>{currentStatusLabel}</span>
            <span className={styles.now} aria-label="Current status">Now</span>
          </li>
        )}
      </ol>
    </section>
  );
}
