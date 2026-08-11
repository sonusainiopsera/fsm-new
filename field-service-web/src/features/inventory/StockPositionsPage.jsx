/**
 * StockPositionsPage — warehouse and vehicle stock position view.
 *
 * Uses the shared DataTable with density control, sticky header, and
 * right-aligned tabular figures for all quantities. Server-side pagination
 * with the 50-item ceiling and allow-listed sorting.
 *
 * ETag-conditional 30-second polling, paused when tab is hidden.
 * Degraded state when as-of exceeds 60 seconds (BR-15).
 *
 * Clicking a row opens the MovementHistoryDrawer for that part + location.
 *
 * SECURITY NOTE (A01): Role-based hiding of transfer, adjustment, and
 * cross-location controls is a usability affordance only. The server
 * enforces authorization on every request.
 *
 * @module features/inventory/StockPositionsPage
 */

import React, { useState, useEffect } from 'react';

import {
  DataTable,
  PageHeader,
  DegradedState,
  EmptyState,
  LoadingState,
  ErrorState,
  PermissionDeniedState,
} from '../../components/index.js';
import { useDashboardQuery } from '../../api/useConditionalQuery.js';
import { listStockPositions } from '../../api/inventory.js';
import { evaluateFreshness, staleDescription } from '../../lib/freshness.js';
import { MovementHistoryDrawer } from './MovementHistoryDrawer.jsx';

import styles from './StockPositionsPage.module.css';

/** Allow-listed sort fields to prevent arbitrary server-side sort injection. */
const ALLOWED_SORTS = new Set(['partNumber', 'quantityOnHand', 'locationName']);

/**
 * @param {{ roles?: string[] }} props
 */
export function StockPositionsPage({ roles = [] }) {
  // SECURITY NOTE (A01): isDispatcher is a usability affordance — controls hidden from
  // technicians and customers are still enforced server-side on every API call.
  const isDispatcher = roles.some((r) => ['DISPATCHER', 'MANAGER', 'ADMIN'].includes(r));

  const [page, setPage] = useState(0);
  const [sort, setSort] = useState(/** @type {{ field: string, direction: 'asc'|'desc' } | null} */ (null));
  const [now, setNow] = useState(() => Date.now());

  // Refresh 'now' for staleness indicator
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(id);
  }, []);

  // Pause polling on hidden tabs
  const [visible, setVisible] = useState(() => !document.hidden);
  useEffect(() => {
    const onVisibility = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  const queryKey = ['stock-positions', { page, sort }];

  const { data, isLoading, isFetching, isError, error, refetch } = useDashboardQuery({
    queryKey,
    queryFn: ({ signal, ifNoneMatch }) =>
      listStockPositions({
        page,
        size: 50,
        sort: sort ? `${sort.field},${sort.direction}` : undefined,
        ifNoneMatch,
      }),
    enabled: visible,
  });

  // Movement history drawer state
  const [drawerRow, setDrawerRow] = useState(/** @type {object | null} */ (null));

  const asOf = data?.asOf ?? null;
  const { isFresh, ageSeconds } = evaluateFreshness(asOf, now);
  const isStale = data && asOf && !isFresh;

  const positions = data?.data ?? [];
  const pagination = data?.page;

  function handleSort(newSort) {
    if (ALLOWED_SORTS.has(newSort.field)) {
      setSort(newSort);
      setPage(0);
    }
  }

  // 403 — generic no-access with no existence disclosure
  if (error?.status === 403) {
    return <PermissionDeniedState />;
  }

  const COLUMNS = [
    { key: 'partNumber', header: 'Part #', sortable: true },
    { key: 'partDescription', header: 'Description' },
    { key: 'locationName', header: 'Location', sortable: true },
    { key: 'locationType', header: 'Type' },
    {
      key: 'quantityOnHand',
      header: 'On Hand',
      numeric: true,
      sortable: true,
      render: (v, row) => (
        <span className={styles.qtyCell}>
          <span className={styles.qty}>{v}</span>
          {row.stockStatus === 'OUT' && (
            <span className={styles.stockOut} aria-label="Stockout">
              {/* Shape: diamond + text + icon — no colour-only encoding (BR-32, BR-34) */}
              <span aria-hidden="true">◆</span> Stockout
            </span>
          )}
          {row.stockStatus === 'LOW' && (
            <span className={styles.stockLow} aria-label="Low stock">
              {/* Shape: triangle + text + icon — distinct from stockout diamond (BR-32, BR-34) */}
              <span aria-hidden="true">▲</span> Low
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'reorderPoint',
      header: 'Reorder At',
      numeric: true,
      render: (v) => <span className={styles.qty}>{v ?? '—'}</span>,
    },
    // Transfer/adjustment controls: hidden for non-dispatcher roles as a usability affordance only.
    // SECURITY NOTE (A01): server enforces authorization — do not treat role check as a security boundary.
    ...(isDispatcher
      ? [{ key: '_history', header: 'History', render: (_v, row) => (
          <button
            type="button"
            className={styles.historyBtn}
            onClick={(e) => { e.stopPropagation(); setDrawerRow(row); }}
            aria-label={`View movement history for ${row.partNumber}`}
          >
            History
          </button>
        )}]
      : []),
  ];

  return (
    <div className={styles.page}>
      <PageHeader title="Stock Positions" />

      {asOf && (
        <p className={styles.asOf} aria-live="polite">
          As of {new Date(asOf).toLocaleTimeString()}
          {isFetching && !isLoading && <span className={styles.updating}> · Updating…</span>}
        </p>
      )}

      {isStale && (
        <DegradedState description={staleDescription('Stock positions', ageSeconds)} />
      )}

      {isLoading && <LoadingState />}

      {isError && !isLoading && (
        <ErrorState
          description="Could not load stock positions."
          onRetry={refetch}
        />
      )}

      {!isLoading && !isError && positions.length === 0 && (
        <EmptyState
          title="No stock positions"
          description="No stock positions are recorded for your accessible locations."
        />
      )}

      {!isLoading && !isError && positions.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            data={positions}
            rowKey={(r) => r.id}
            onSort={handleSort}
            sort={sort}
            onRowClick={isDispatcher ? setDrawerRow : undefined}
            caption="Stock positions by location"
          />

          {pagination && pagination.totalPages > 1 && (
            <div className={styles.pagination} role="navigation" aria-label="Stock position pages">
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

      <MovementHistoryDrawer
        open={!!drawerRow}
        onClose={() => setDrawerRow(null)}
        partId={drawerRow?.partId}
        partNumber={drawerRow?.partNumber}
        locationId={drawerRow?.locationId}
        locationName={drawerRow?.locationName}
      />
    </div>
  );
}
