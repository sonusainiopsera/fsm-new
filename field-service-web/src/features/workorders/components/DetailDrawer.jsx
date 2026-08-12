/**
 * Work-order detail drawer — level-2 modal overlay.
 *
 * Deep-linkable: the drawer ID is stored in the URL search param `drawerId`.
 * Closes on Escape, overlay click, and close button.
 * Focus trapped; focus returns to the invoking row on close.
 *
 * Lifecycle action buttons are rendered SOLELY from the legalNextEvents list
 * returned by the server. No lifecycle rule is reimplemented here (BR-constraint).
 *
 * @module features/workorders/components/DetailDrawer
 */

import React, { useId, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import { DetailDrawer as BaseDrawer } from '../../../components/index.js';
import { Chip } from '../../../components/index.js';
import { apiFetch } from '../../../api/http.js';
import { useWorkOrderTransition } from '../api/useWorkOrderTransition.js';

import styles from './DetailDrawer.module.css';

/**
 * Fetches a single work order by id.
 * @param {string} id
 * @param {AbortSignal} signal
 */
async function fetchWorkOrder(id, signal) {
  return apiFetch(`/work-orders/${id}`, { signal });
}

/**
 * Human-readable label for a lifecycle event code.
 * @param {string} event
 * @returns {string}
 */
function eventLabel(event) {
  const labels = {
    ASSIGN:    'Assign',
    DEPART:    'Mark En Route',
    START:     'Start Work',
    HOLD:      'Place on Hold',
    RESUME:    'Resume',
    COMPLETE:  'Complete',
    CLOSE:     'Close',
    CANCEL:    'Cancel',
    REOPEN:    'Reopen',
  };
  return labels[event] ?? event.charAt(0) + event.slice(1).toLowerCase().replace(/_/g, ' ');
}

/**
 * Visual emphasis class for a lifecycle event.
 * @param {string} event
 * @returns {string}
 */
function eventVariant(event) {
  if (['CANCEL', 'CLOSE'].includes(event)) return styles.actionDanger;
  if (['COMPLETE'].includes(event))        return styles.actionSuccess;
  return styles.actionPrimary;
}

/**
 * Inline refusal banner.
 * @param {{ refusal: import('../api/useWorkOrderTransition.js').TransitionRefusal | null }} props
 */
function RefusalBanner({ refusal }) {
  if (!refusal) return null;
  const isReload = refusal.type === 'VERSION_CONFLICT';
  return (
    <div className={styles.refusalBanner} role="alert" aria-live="assertive">
      <span aria-hidden="true">{isReload ? '⚠' : '✕'}</span>
      {' '}{refusal.message}
      {isReload && (
        <button
          type="button"
          className={styles.reloadBtn}
          onClick={() => window.location.reload()}
        >
          Reload now
        </button>
      )}
    </div>
  );
}

/**
 * @param {{
 *   workOrderId: string | null,
 *   onClose: () => void,
 * }} props
 */
export function WorkOrderDetailDrawer({ workOrderId, onClose }) {
  const [refusal, setRefusal] = useState(null);
  const [pendingEvent, setPendingEvent] = useState(null);
  const qc = useQueryClient();

  const { data: wo, isLoading, isError, error } = useQuery({
    queryKey: ['work-orders', 'detail', workOrderId],
    queryFn: ({ signal }) => fetchWorkOrder(workOrderId, signal),
    enabled: !!workOrderId,
  });

  const { transition, isPending, reset: resetMutation } = useWorkOrderTransition({
    onSuccess: () => {
      setRefusal(null);
      setPendingEvent(null);
      // Board list is already invalidated by the hook
    },
    onRefusal: (r) => {
      setRefusal(r);
      setPendingEvent(null);
    },
  });

  function handleAction(event) {
    if (!wo) return;
    setRefusal(null);
    resetMutation();
    setPendingEvent(event);
    transition({
      workOrderId: wo.id,
      event,
      expectedVersion: wo.version,
    });
  }

  const open = !!workOrderId;

  // Build drawer title
  const title = wo?.reference ? `Work Order ${wo.reference}` : 'Work Order Detail';

  // Lifecycle actions from server-supplied legalNextEvents — no client-side guess
  const legalNextEvents = wo?.legalNextEvents ?? [];

  const footer = (
    <div className={styles.footer}>
      {legalNextEvents.length === 0 && !isLoading && (
        <span className={styles.noActions}>No actions available</span>
      )}
      {legalNextEvents.map((event) => (
        <button
          key={event}
          type="button"
          className={[styles.actionBtn, eventVariant(event)].join(' ')}
          onClick={() => handleAction(event)}
          disabled={isPending}
          aria-label={`${eventLabel(event)} work order ${wo?.reference ?? ''}`}
          aria-busy={isPending && pendingEvent === event}
        >
          {isPending && pendingEvent === event ? 'Working…' : eventLabel(event)}
        </button>
      ))}
      <RefusalBanner refusal={refusal} />
    </div>
  );

  return (
    <BaseDrawer open={open} title={title} onClose={onClose} footer={footer}>
      {isLoading && (
        <div className={styles.loading} role="status" aria-live="polite">
          <span className="sr-only">Loading work order details…</span>
          <div className={styles.skeleton} aria-hidden="true" />
          <div className={styles.skeleton} aria-hidden="true" />
          <div className={styles.skeleton} aria-hidden="true" />
        </div>
      )}

      {isError && !isLoading && (
        <div className={styles.drawerError} role="alert">
          {error?.status === 403
            ? 'This work order is not available.'
            : 'Could not load work order details.'}
        </div>
      )}

      {wo && !isLoading && (
        <dl className={styles.detail}>
          <div className={styles.detailRow}>
            <dt>Reference</dt>
            <dd className={styles.mono}>{wo.reference}</dd>
          </div>
          <div className={styles.detailRow}>
            <dt>State</dt>
            <dd><Chip variant="state" value={wo.state?.toLowerCase()} /></dd>
          </div>
          <div className={styles.detailRow}>
            <dt>Priority</dt>
            <dd><Chip variant="priority" value={wo.priority?.toLowerCase()} /></dd>
          </div>
          <div className={styles.detailRow}>
            <dt>Customer</dt>
            <dd className={styles.truncate} title={wo.customerName}>{wo.customerName ?? '—'}</dd>
          </div>
          <div className={styles.detailRow}>
            <dt>Site</dt>
            <dd className={styles.truncate} title={wo.siteName}>{wo.siteName ?? '—'}</dd>
          </div>
          <div className={styles.detailRow}>
            <dt>Technician</dt>
            <dd>{wo.assignedTechnicianName ?? <span className={styles.unassigned}>Unassigned</span>}</dd>
          </div>
          {wo.responseDeadline && (
            <div className={styles.detailRow}>
              <dt>Response by</dt>
              <dd>{new Date(wo.responseDeadline).toLocaleString()}</dd>
            </div>
          )}
          {wo.resolutionDeadline && (
            <div className={styles.detailRow}>
              <dt>Resolve by</dt>
              <dd>{new Date(wo.resolutionDeadline).toLocaleString()}</dd>
            </div>
          )}
          {wo.atRisk && (
            <div className={styles.detailRow}>
              <dt>Risk</dt>
              <dd className={styles.atRisk}>
                <span aria-hidden="true">⬥</span> At risk
              </dd>
            </div>
          )}
          {wo.description && (
            <div className={styles.detailRowFull}>
              <dt>Description</dt>
              <dd>{wo.description}</dd>
            </div>
          )}
          {wo.faultCode && (
            <div className={styles.detailRow}>
              <dt>Fault code</dt>
              <dd className={styles.mono}>{wo.faultCode}</dd>
            </div>
          )}
          {wo.faultCategory && (
            <div className={styles.detailRow}>
              <dt>Category</dt>
              <dd>{wo.faultCategory}</dd>
            </div>
          )}
          <div className={styles.detailRow}>
            <dt>Created</dt>
            <dd>{wo.createdAt ? new Date(wo.createdAt).toLocaleString() : '—'}</dd>
          </div>
        </dl>
      )}
    </BaseDrawer>
  );
}
