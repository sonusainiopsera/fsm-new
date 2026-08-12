import React from 'react';
import styles from './ReconciliationBanner.module.css';

/** @import { ReconciliationDto } from '../api/useDrillDownWorkOrders.js' */

const REASON_MESSAGES = {
  PROVISIONAL_COHORT:
    'This metric uses provisional data. Recent work orders may shift the count as they close.',
  READ_MODEL_STALE:
    'The dashboard value was computed from an earlier snapshot. The list reflects live data.',
  SCOPE_RESTRICTED:
    'Your access is scoped to a subset of the population that produced this metric.',
  NO_WIDGET_DATA:
    'No widget value is available for comparison — the list reflects live data.',
};

/**
 * Reconciliation banner displayed below the drill-down page header.
 *
 * Shows MATCHED state as a subtle confirmation, DIVERGED state as an
 * informational notice with a plain-language reason. Never exposes how many
 * records were excluded (security: no existence disclosure).
 *
 * @param {{
 *   reconciliation: ReconciliationDto | null | undefined,
 * }} props
 */
export function ReconciliationBanner({ reconciliation }) {
  if (!reconciliation) return null;

  const { status, reason, widgetValue, widgetDataAsOf, resultCount } = reconciliation;

  if (status === 'MATCHED') {
    return (
      <div className={`${styles.banner} ${styles.matched}`} role="status" aria-live="polite">
        <span className={styles.icon} aria-hidden="true">✓</span>
        <span>
          List count matches the dashboard value ({resultCount.toLocaleString()} work orders).
        </span>
      </div>
    );
  }

  // DIVERGED
  const message = REASON_MESSAGES[reason] ?? 'The list count differs from the dashboard value.';
  const dataAge = widgetDataAsOf
    ? new Date(widgetDataAsOf).toLocaleString(undefined, {
        dateStyle: 'short',
        timeStyle: 'short',
      })
    : null;

  return (
    <div className={`${styles.banner} ${styles.diverged}`} role="status" aria-live="polite">
      <div className={styles.content}>
        <span className={styles.icon} aria-hidden="true">ⓘ</span>
        <div>
          <p className={styles.mainMessage}>{message}</p>
          {widgetValue != null && (
            <p className={styles.detail}>
              Dashboard value: <strong>{widgetValue.toLocaleString()}</strong>
              {dataAge ? ` (as of ${dataAge})` : ''}
              {' · '}
              List count: <strong>{resultCount.toLocaleString()}</strong>
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
