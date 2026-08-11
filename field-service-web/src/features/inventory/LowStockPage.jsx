/**
 * LowStockPage — active low-stock and stockout alerts.
 *
 * Renders the WO-056 alerts endpoint. Every indicator carries a text label,
 * an icon, AND a distinct shape so meaning never depends on colour alone (BR-32, BR-34).
 * Contrast verified in both light and dark appearances.
 *
 * ETag-conditional 30-second polling, paused when the tab is hidden.
 * Degraded state triggers when as-of exceeds 60 seconds (BR-15).
 *
 * SECURITY NOTE (A01): Role-based hiding of transfer/adjustment controls is a
 * usability affordance only. The server enforces authorization on every request.
 *
 * @module features/inventory/LowStockPage
 */

import React, { useState, useEffect } from 'react';

import {
  DataTable,
  PageHeader,
  Chip,
  DegradedState,
  EmptyState,
  LoadingState,
  ErrorState,
} from '../../components/index.js';
import { useDashboardQuery } from '../../api/useConditionalQuery.js';
import { listStockAlerts } from '../../api/inventory.js';
import { evaluateFreshness, staleDescription } from '../../lib/freshness.js';

import styles from './LowStockPage.module.css';

/**
 * Renders the stock status indicator.
 * Uses text + icon + shape (triangle vs circle) so no colour-only encoding.
 *
 * @param {{ status: 'LOW' | 'OUT' }} props
 */
function StockStatusIndicator({ status }) {
  if (status === 'OUT') {
    return (
      <span className={styles.statusOut} title="Out of stock" role="img" aria-label="Out of stock">
        {/* Diamond shape — distinct from the LOW triangle */}
        <span className={styles.statusShape} aria-hidden="true">◆</span>
        <span className={styles.statusLabel}>Stockout</span>
      </span>
    );
  }
  return (
    <span className={styles.statusLow} title="Low stock" role="img" aria-label="Low stock">
      {/* Triangle shape */}
      <span className={styles.statusShape} aria-hidden="true">▲</span>
      <span className={styles.statusLabel}>Low</span>
    </span>
  );
}

const COLUMNS = [
  { key: 'stockStatus', header: 'Status', render: (v) => <StockStatusIndicator status={v} /> },
  { key: 'partNumber', header: 'Part #', sortable: true },
  { key: 'partDescription', header: 'Description' },
  { key: 'locationName', header: 'Location' },
  {
    key: 'quantityOnHand',
    header: 'On Hand',
    numeric: true,
    sortable: true,
    render: (v) => <span className={styles.qty}>{v}</span>,
  },
  {
    key: 'reorderPoint',
    header: 'Reorder At',
    numeric: true,
    render: (v) => <span className={styles.qty}>{v ?? '—'}</span>,
  },
  { key: 'raisedAt', header: 'Since', render: (v) => new Date(v).toLocaleDateString() },
];

const ALLOWED_SORTS = new Set(['partNumber', 'quantityOnHand']);

/**
 * @param {{ roles?: string[] }} props
 */
export function LowStockPage({ roles = [] }) {
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState(/** @type {{ field: string, direction: 'asc'|'desc' } | null} */ (null));
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(id);
  }, []);

  const [visible, setVisible] = useState(() => !document.hidden);
  useEffect(() => {
    const onVisibility = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  const queryKey = ['stock-alerts', { page, sort }];

  const { data, isLoading, isFetching, isError, refetch } = useDashboardQuery({
    queryKey,
    queryFn: ({ signal, ifNoneMatch }) => listStockAlerts({ page, size: 50, ifNoneMatch }),
    enabled: visible,
  });

  const asOf = data?.asOf ?? null;
  const { isFresh, ageSeconds } = evaluateFreshness(asOf, now);
  const isStale = data && asOf && !isFresh;

  const alerts = data?.data ?? [];
  const pagination = data?.page;

  function handleSort(newSort) {
    if (ALLOWED_SORTS.has(newSort.field)) {
      setSort(newSort);
      setPage(0);
    }
  }

  // Client-side sort for the current page only (server is authoritative for multi-page)
  const sortedAlerts = sort
    ? [...alerts].sort((a, b) => {
        const v1 = a[sort.field];
        const v2 = b[sort.field];
        const cmp = typeof v1 === 'number' ? v1 - v2 : String(v1 ?? '').localeCompare(String(v2 ?? ''));
        return sort.direction === 'asc' ? cmp : -cmp;
      })
    : alerts;

  return (
    <div className={styles.page}>
      <PageHeader title="Low Stock Alerts" />

      {asOf && (
        <p className={styles.asOf} aria-live="polite">
          As of {new Date(asOf).toLocaleTimeString()}
          {isFetching && !isLoading && <span className={styles.updating}> · Updating…</span>}
        </p>
      )}

      {isStale && (
        <DegradedState description={staleDescription('Low-stock alerts', ageSeconds)} />
      )}

      {isLoading && <LoadingState />}

      {isError && !isLoading && (
        <ErrorState
          description="Could not load stock alerts."
          onRetry={refetch}
        />
      )}

      {!isLoading && !isError && alerts.length === 0 && (
        <EmptyState
          title="No active alerts"
          description="There are no active low-stock or stockout alerts."
        />
      )}

      {!isLoading && !isError && alerts.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            data={sortedAlerts}
            rowKey={(r) => r.id}
            onSort={handleSort}
            sort={sort}
            caption="Active low-stock and stockout alerts"
          />

          {pagination && pagination.totalPages > 1 && (
            <div className={styles.pagination} role="navigation" aria-label="Alert list pages">
              <button
                type="button"
                className={styles.pageBtn}
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                aria-label="Previous page"
              >
                ← Previous
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
        </>
      )}
    </div>
  );
}
