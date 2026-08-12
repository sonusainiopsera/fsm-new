/**
 * ErrorSummary — renders unmapped API field errors and the traceId.
 *
 * Used when a server 400/422 returns fieldErrors that don't match
 * any known form field, or for a top-level message with no field errors.
 *
 * @module features/admin/ErrorSummary
 */

import React from 'react';
import styles from './admin.module.css';

/**
 * @param {{
 *   errors: string[],
 *   traceId?: string | null,
 *   title?: string,
 * }} props
 */
export function ErrorSummary({ errors, traceId = null, title = 'Please correct the following errors:' }) {
  if (!errors || errors.length === 0) return null;

  return (
    <div className={styles.errorSummary} role="alert" aria-live="polite">
      <div className={styles.errorSummaryTitle}>{title}</div>
      <ul>
        {errors.map((msg, i) => (
          <li key={i}>{msg}</li>
        ))}
      </ul>
      {traceId && (
        <div className={styles.errorTraceId}>Reference: {traceId}</div>
      )}
    </div>
  );
}
