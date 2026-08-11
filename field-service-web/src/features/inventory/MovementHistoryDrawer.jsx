/**
 * MovementHistoryDrawer — part movement history in a side drawer.
 *
 * Fetches WO-054 movement API filtered by part and/or location.
 * Uses ETag-conditional 30-second polling (paused when tab is hidden).
 * Displays degraded state when as-of timestamp exceeds the 60-second budget.
 *
 * @module features/inventory/MovementHistoryDrawer
 */

import React, { useState, useEffect } from 'react';

import { DetailDrawer, DegradedState, EmptyState, LoadingState, ErrorState } from '../../components/index.js';
import { useDashboardQuery } from '../../api/useConditionalQuery.js';
import { listMovements } from '../../api/inventory.js';
import { evaluateFreshness, staleDescription } from '../../lib/freshness.js';

import styles from './MovementHistoryDrawer.module.css';

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   partId?: string,
 *   partNumber?: string,
 *   locationId?: string,
 *   locationName?: string,
 * }} props
 */
export function MovementHistoryDrawer({ open, onClose, partId, partNumber, locationId, locationName }) {
  const [page, setPage] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  // Keep 'now' updated for staleness calculations
  useEffect(() => {
    if (!open) return;
    const id = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(id);
  }, [open]);

  // Pause polling when tab is hidden
  const [visible, setVisible] = useState(() => !document.hidden);
  useEffect(() => {
    const onVisibility = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  const queryKey = ['movements', { partId, locationId, page }];

  const { data, isFetching, isLoading, isError, refetch } = useDashboardQuery({
    queryKey,
    queryFn: ({ signal, ifNoneMatch }) =>
      listMovements({ partId, locationId, page, size: 50, ifNoneMatch }),
    enabled: open && visible,
  });

  const asOf = data?.data?.[0]?.occurredAt ?? null;
  const { isFresh, ageSeconds } = evaluateFreshness(asOf, now);
  const isStale = data && asOf && !isFresh;

  const title = [
    'Movement History',
    partNumber ? `— ${partNumber}` : '',
    locationName ? `@ ${locationName}` : '',
  ].filter(Boolean).join(' ');

  const movements = data?.data ?? [];
  const pagination = data?.page;

  return (
    <DetailDrawer open={open} title={title} onClose={onClose}>
      {isLoading && <LoadingState />}

      {isError && !isLoading && (
        <ErrorState
          description="Could not load movement history."
          onRetry={refetch}
        />
      )}

      {!isLoading && !isError && isStale && (
        <DegradedState
          description={staleDescription('Movement history', ageSeconds)}
        />
      )}

      {!isLoading && !isError && movements.length === 0 && (
        <EmptyState
          title="No movements"
          description="No movement history found for this selection."
        />
      )}

      {!isLoading && !isError && movements.length > 0 && (
        <div>
          {isFetching && !isLoading && (
            <p className={styles.updating} role="status" aria-live="polite">Updating…</p>
          )}

          <table className={styles.table} aria-label="Movement history">
            <thead>
              <tr>
                <th scope="col">Date</th>
                <th scope="col">Type</th>
                <th scope="col" className={styles.numericCol}>Qty</th>
                <th scope="col">Reason</th>
                <th scope="col">By</th>
              </tr>
            </thead>
            <tbody>
              {movements.map((m) => (
                <tr key={m.id} className={styles.row}>
                  <td>{new Date(m.occurredAt).toLocaleString()}</td>
                  <td>{m.movementType}</td>
                  <td className={styles.numericCol}>{m.quantity}</td>
                  <td>{m.reasonCode}</td>
                  <td>{m.performedBy}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {pagination && pagination.totalPages > 1 && (
            <div className={styles.pagination} role="navigation" aria-label="Movement history pages">
              <button
                type="button"
                className={styles.pageBtn}
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                aria-label="Previous page"
              >
                ← Prev
              </button>
              <span className={styles.pageInfo}>
                Page {pagination.number + 1} of {pagination.totalPages}
              </span>
              <button
                type="button"
                className={styles.pageBtn}
                disabled={page >= pagination.totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
                aria-label="Next page"
              >
                Next →
              </button>
            </div>
          )}
        </div>
      )}
    </DetailDrawer>
  );
}
