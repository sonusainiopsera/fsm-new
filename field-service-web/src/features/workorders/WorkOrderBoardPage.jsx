/**
 * WorkOrderBoardPage — dispatcher work order board.
 *
 * URL is the single source of board state: filters, sort, page, and the
 * open drawer ID are all read from and written to URL search params so
 * back/forward navigation and link-sharing work correctly.
 *
 * Data is polled every 30 seconds via a conditional GET (ETag). A 304
 * response triggers no re-render and no layout shift.
 *
 * Lifecycle actions are delegated entirely to the server's legalNextEvents
 * list — no lifecycle rule is reimplemented in the frontend.
 *
 * @module features/workorders/WorkOrderBoardPage
 */

import React, { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

import {
  PageHeader,
  EmptyState,
  LoadingState,
  ErrorState,
  PermissionDeniedState,
  DegradedState,
} from '../../components/index.js';
import { useNetworkStatus } from '../../app/useNetworkStatus.js';

import { useWorkOrderSearch } from './api/useWorkOrderSearch.js';
import { FilterBar } from './components/FilterBar.jsx';
import { WorkOrderTable } from './components/WorkOrderTable.jsx';
import { WorkOrderDetailDrawer } from './components/DetailDrawer.jsx';

import styles from './WorkOrderBoardPage.module.css';

/**
 * Dispatcher work order board.
 * URL search params are the single source of truth for all board state.
 */
export default function WorkOrderBoardPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { isOnline } = useNetworkStatus();

  const {
    data,
    pageMeta,
    isLoading,
    isFetching,
    isError,
    error,
    state,
    setPage,
    setSort,
    setFilter,
    refetch,
  } = useWorkOrderSearch();

  // Drawer ID stored in URL so deep-link works
  const drawerId = searchParams.get('drawerId') ?? null;

  const openDrawer = useCallback((row) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('drawerId', row.id);
      return next;
    }, { replace: false }); // use push so back-button closes the drawer
  }, [setSearchParams]);

  const closeDrawer = useCallback(() => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('drawerId');
      return next;
    }, { replace: false });
  }, [setSearchParams]);

  const hasFilters =
    state.filters.states.length > 0 ||
    state.filters.priorities.length > 0 ||
    state.filters.technicianId ||
    state.filters.customerId ||
    state.filters.dateFrom ||
    state.filters.dateTo ||
    state.filters.atRisk;

  if (error?.status === 403) {
    return <PermissionDeniedState />;
  }

  return (
    <div className={styles.page}>
      <PageHeader
        title="Dispatch Board"
        subtitle={
          pageMeta.totalElements > 0
            ? `${pageMeta.totalElements} work order${pageMeta.totalElements !== 1 ? 's' : ''}`
            : undefined
        }
      />

      {/* Non-blocking not-connected indicator */}
      {!isOnline && (
        <div className={styles.offlineBanner} role="status" aria-live="polite">
          <span aria-hidden="true">⚡</span> Offline — showing last known data. Polling will resume when connected.
        </div>
      )}

      {/* Non-blocking refetch indicator */}
      {isFetching && !isLoading && (
        <div className={styles.refreshing} role="status" aria-live="polite" aria-label="Refreshing board…">
          <span className={styles.refreshDot} aria-hidden="true" /> Updating…
        </div>
      )}

      <FilterBar filters={state.filters} setFilter={setFilter} />

      {isLoading && <LoadingState />}

      {isError && !isLoading && (
        <ErrorState
          description="Could not load the dispatch board."
          onRetry={refetch}
        />
      )}

      {!isLoading && !isError && data.length === 0 && (
        <EmptyState
          title={hasFilters ? 'No matching work orders' : 'No work orders'}
          description={
            hasFilters
              ? 'No work orders match the current filters.'
              : 'There are no open work orders right now.'
          }
          onRetry={hasFilters ? () => {
            setFilter('states',      []);
            setFilter('priorities',  []);
            setFilter('technicianId', null);
            setFilter('customerId',   null);
            setFilter('dateFrom',     null);
            setFilter('dateTo',       null);
            setFilter('atRisk',       false);
          } : undefined}
          retryLabel={hasFilters ? 'Clear filters' : undefined}
        />
      )}

      {!isLoading && !isError && data.length > 0 && (
        <>
          <WorkOrderTable
            data={data}
            sort={state.sort}
            selectedId={drawerId}
            onRowClick={openDrawer}
            onSort={setSort}
          />

          {pageMeta.totalPages > 1 && (
            <nav className={styles.pagination} aria-label="Work order pages">
              <button
                type="button"
                className={styles.pageBtn}
                disabled={state.page === 0}
                onClick={() => setPage(state.page - 1)}
                aria-label="Previous page"
              >
                ← Previous
              </button>
              <span className={styles.pageInfo}>
                Page {pageMeta.number + 1} of {pageMeta.totalPages}
                <span className="sr-only"> ({pageMeta.totalElements} total)</span>
              </span>
              <button
                type="button"
                className={styles.pageBtn}
                disabled={state.page >= pageMeta.totalPages - 1}
                onClick={() => setPage(state.page + 1)}
                aria-label="Next page"
              >
                Next →
              </button>
            </nav>
          )}
        </>
      )}

      {/* Deep-linkable detail drawer — does not unmount or refetch the board */}
      <WorkOrderDetailDrawer
        workOrderId={drawerId}
        onClose={closeDrawer}
      />
    </div>
  );
}
