import React from 'react';
import styles from './PartsWarningNotice.module.css';

/**
 * Advisory banner shown when parts availability data indicates a risk.
 *
 * @param {{
 *   warning: { message?: string, partNumber?: string } | null | undefined,
 *   id?: string,
 * }} props
 */
export function PartsWarningNotice({ warning, id }) {
  if (!warning) return null;

  const text = warning.message
    ?? (warning.partNumber
      ? `Part ${warning.partNumber} may be unavailable on this technician's vehicle.`
      : 'Some required parts may be unavailable.');

  return (
    <div id={id} className={styles.notice} role="status" aria-live="polite">
      <span className={styles.icon} aria-hidden="true">⚠</span>
      <span className={styles.text}>{text}</span>
    </div>
  );
}
