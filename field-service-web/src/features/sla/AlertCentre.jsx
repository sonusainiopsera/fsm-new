/**
 * AlertCentre — panel listing open at-risk and breached work orders.
 *
 * Sorted by urgency: breached first, then by priority, then by least
 * time remaining. Receives stream status from the parent and passes it
 * down to chips so stale data is always labelled explicitly.
 *
 * Data is fetched from GET /api/v1/sla/open-alerts; SSE events invalidate
 * the ['sla-alerts'] query key via useSlaAlertStream so the server remains
 * the source of truth.
 *
 * @module features/sla/AlertCentre
 */

import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '../../api/http.js';
import { SlaRiskChip } from './SlaRiskChip.jsx';
import { BreachReasonDialog } from './BreachReasonDialog.jsx';

import styles from './AlertCentre.module.css';

/** @typedef {import('./useSlaAlertStream.js').StreamStatus} StreamStatus */

const PRIORITY_ORDER = { urgent: 0, high: 1, normal: 2, low: 3 };

/**
 * Sorts alerts by: riskState (breached first), then priority, then minutesRemaining (ascending).
 *
 * @param {object[]} alerts
 * @returns {object[]}
 */
function sortByUrgency(alerts) {
  return [...alerts].sort((a, b) => {
    // 1. Breached before at-risk
    const aBreached = a.riskState === 'breached' ? 0 : 1;
    const bBreached = b.riskState === 'breached' ? 0 : 1;
    if (aBreached !== bBreached) return aBreached - bBreached;

    // 2. Higher priority first
    const aPri = PRIORITY_ORDER[a.priority?.toLowerCase()] ?? 99;
    const bPri = PRIORITY_ORDER[b.priority?.toLowerCase()] ?? 99;
    if (aPri !== bPri) return aPri - bPri;

    // 3. Less time remaining first (overrun = negative, sorts lowest)
    const aMins = a.minutesRemaining ?? Infinity;
    const bMins = b.minutesRemaining ?? Infinity;
    return aMins - bMins;
  });
}

/**
 * @param {{
 *   streamStatus: StreamStatus,
 *   onNavigate?: (workOrderId: string) => void,
 *   userRoles?: string[],
 * }} props
 */
export function AlertCentre({ streamStatus, onNavigate, userRoles = [] }) {
  const [breachDialog, setBreachDialog] = useState(/** @type {{ id: string, ref: string } | null} */ (null));

  const { data, isLoading, isError } = useQuery({
    queryKey: ['sla-alerts'],
    queryFn: () => apiFetch('/sla/open-alerts'),
    staleTime: 30_000,
  });

  const alerts    = data?.data ?? [];
  const sorted    = sortByUrgency(alerts);
  const stale     = streamStatus === 'stale';
  const canAttrib = userRoles.some((r) => r === 'DISPATCHER' || r === 'ADMIN');

  return (
    <section className={styles.panel} aria-label="SLA alert centre">
      <header className={styles.header}>
        <h2 className={styles.title}>SLA Alerts</h2>
        {sorted.length > 0 && (
          <span
            className={styles.badge}
            aria-label={`${sorted.length} open alert${sorted.length !== 1 ? 's' : ''}`}
          >
            {sorted.length}
          </span>
        )}
        {stale && (
          <span className={styles.staleNote} aria-live="polite">
            <span aria-hidden="true">⊘</span> Data may be out of date
          </span>
        )}
      </header>

      {isLoading && (
        <div className={styles.empty} role="status" aria-live="polite">
          Loading alerts…
        </div>
      )}

      {isError && !isLoading && (
        <div className={styles.empty} role="alert">
          Could not load SLA alerts.
        </div>
      )}

      {!isLoading && !isError && sorted.length === 0 && (
        <div className={styles.empty}>
          <span aria-hidden="true">✓</span>{' '}No open SLA alerts
        </div>
      )}

      {!isLoading && !isError && sorted.length > 0 && (
        <ul className={styles.list} aria-label="Open SLA alerts, sorted by urgency">
          {sorted.map((alert) => (
            <li key={alert.workOrderId} className={styles.item}>
              <div className={styles.chipRow}>
                <SlaRiskChip
                  riskState={alert.riskState}
                  minutesRemaining={alert.minutesRemaining}
                  stale={stale}
                />
                {alert.priority && (
                  <span className={styles.priority}>{alert.priority}</span>
                )}
              </div>

              <div className={styles.details}>
                <span
                  className={styles.reference}
                  title={alert.reference}
                >
                  {alert.reference}
                </span>

                {alert.triggerReason && (
                  <span
                    className={styles.reason}
                    title={alert.triggerReason}
                    aria-label={`Trigger reason: ${alert.triggerReason}`}
                  >
                    {alert.triggerReason}
                  </span>
                )}

                {alert.projectionBasis && (
                  <span
                    className={styles.basis}
                    title={alert.projectionBasis}
                    aria-label={`Projection basis: ${alert.projectionBasis}`}
                  >
                    {alert.projectionBasis}
                  </span>
                )}
              </div>

              <div className={styles.actions}>
                {onNavigate && (
                  <button
                    type="button"
                    className={styles.navBtn}
                    onClick={() => onNavigate(alert.workOrderId)}
                    aria-label={`View work order ${alert.reference}`}
                  >
                    View →
                  </button>
                )}
                {canAttrib && alert.riskState === 'breached' && (
                  <button
                    type="button"
                    className={styles.attribBtn}
                    onClick={() =>
                      setBreachDialog({ id: alert.workOrderId, ref: alert.reference })
                    }
                    aria-label={`Attribute breach reason for ${alert.reference}`}
                  >
                    Attribute
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {breachDialog && (
        <BreachReasonDialog
          open={!!breachDialog}
          workOrderId={breachDialog.id}
          workOrderRef={breachDialog.ref}
          onClose={() => setBreachDialog(null)}
          onSuccess={() => setBreachDialog(null)}
        />
      )}
    </section>
  );
}
