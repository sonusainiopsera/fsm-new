import React, { useCallback, useMemo } from 'react';
import { useSearchParams, Link } from 'react-router-dom';

import {
  PageHeader,
  DataTable,
  LoadingState,
  EmptyState,
  DegradedState,
  ErrorState,
} from '../../components/index.js';
import { useDrillDownWorkOrders } from './api/useDrillDownWorkOrders.js';
import { ReconciliationBanner } from './components/ReconciliationBanner.jsx';
import styles from './DrillDownPage.module.css';

/** Human-readable labels for metric keys */
const METRIC_LABELS = {
  SLA_COMPLIANCE_RATE:       'SLA Compliance Rate',
  SLA_BREACH_COUNT:          'SLA Breach Count',
  SLA_RESOLUTION_MEAN:       'Mean Resolution Time',
  SLA_RESOLUTION_MEDIAN:     'Median Resolution Time',
  FIRST_TIME_FIX_RATE:       'First-Time Fix Rate',
  FIRST_TIME_FIX_PROVISIONAL:'First-Time Fix (Provisional)',
  REPEAT_VISIT_COUNT:        'Repeat Visit Count',
  BACKLOG_OPEN_COUNT:        'Open Backlog',
  BACKLOG_ON_HOLD_COUNT:     'On-Hold Backlog',
  UTILIZATION_RATE:          'Utilization Rate',
  JOBS_PER_DAY:              'Jobs Per Day',
  WORKLOAD_BALANCE:          'Workload Balance',
};

/** Human-readable labels for window values */
const WINDOW_LABELS = {
  SEVEN_DAYS:  'Last 7 days',
  THIRTY_DAYS: 'Last 30 days',
  NINETY_DAYS: 'Last 90 days',
};

/** Column definitions for the work order data table */
const COLUMNS = [
  { key: 'reference',   header: 'Reference', sortable: true },
  { key: 'state',       header: 'State',     sortable: true },
  { key: 'priority',    header: 'Priority',  sortable: true },
  { key: 'siteName',    header: 'Site' },
  { key: 'assignedTechnicianName', header: 'Technician' },
  {
    key: 'createdAt',
    header: 'Created',
    sortable: true,
    render: (v) => v ? new Date(v).toLocaleDateString() : '—',
  },
  {
    key: 'resolutionDeadline',
    header: 'Deadline',
    render: (v) => v ? new Date(v).toLocaleDateString() : '—',
  },
];

const VALID_WINDOWS = new Set(['SEVEN_DAYS', 'THIRTY_DAYS', 'NINETY_DAYS']);
const VALID_SORT_FIELDS = new Set(['createdAt', 'priority', 'state', 'reference', 'resolutionDeadline']);

/**
 * KPI drill-down page.
 *
 * URL params:
 *   ?metric=BACKLOG_OPEN_COUNT  — allow-listed metric key (required)
 *   ?window=THIRTY_DAYS         — observation window (required)
 *   ?segment=PRIORITY:HIGH      — optional segment
 *   ?page=0                     — zero-based page number
 *   ?size=20                    — page size (server-clamped to 50)
 *   ?sort=createdAt:desc        — sort field:direction
 *
 * @returns {JSX.Element}
 */
export default function DrillDownPage() {
  const [params, setParams] = useSearchParams();

  const metric  = params.get('metric')  ?? '';
  const win     = params.get('window')  ?? '';
  const segment = params.get('segment') ?? 'ALL';
  const page    = Math.max(0, parseInt(params.get('page') ?? '0', 10));
  const size    = Math.min(50, Math.max(1, parseInt(params.get('size') ?? '20', 10)));
  const sort    = params.get('sort') ?? 'createdAt:desc';

  const isValidParams = Boolean(metric) && VALID_WINDOWS.has(win);

  const { data, isPending, isError, error, isFetching, refetch } = useDrillDownWorkOrders({
    metric,
    window: win,
    segment,
    page,
    size,
    sort,
    enabled: isValidParams,
  });

  const handleSort = useCallback(({ field, direction }) => {
    if (!VALID_SORT_FIELDS.has(field)) return;
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('sort', `${field}:${direction}`);
      next.set('page', '0');
      return next;
    });
  }, [setParams]);

  const handleRemoveSegment = useCallback(() => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('segment');
      next.set('page', '0');
      return next;
    });
  }, [setParams]);

  const currentSort = useMemo(() => {
    const [field, direction] = (sort ?? '').split(':');
    if (VALID_SORT_FIELDS.has(field) && (direction === 'asc' || direction === 'desc')) {
      return { field, direction };
    }
    return { field: 'createdAt', direction: 'desc' };
  }, [sort]);

  const rows        = data?.data ?? [];
  const pageMeta    = data?.page;
  const reconciliation = data?.reconciliation ?? null;

  const metricLabel  = METRIC_LABELS[metric] ?? metric;
  const windowLabel  = WINDOW_LABELS[win]    ?? win;
  const segmentLabel = segment && segment !== 'ALL' ? segment : null;

  // ── Invalid parameters ────────────────────────────────────────────────────
  if (!isValidParams) {
    return (
      <main className={styles.page}>
        <PageHeader
          title="Work Order List"
          subtitle={<Link to="/operations/dashboard">← Back to Dashboard</Link>}
        />
        <ErrorState description="Invalid drill-down parameters. Please navigate from the dashboard." />
      </main>
    );
  }

  // ── Loading state (first load) ────────────────────────────────────────────
  if (isPending) {
    return (
      <main className={styles.page}>
        <PageHeader
          title={metricLabel}
          subtitle={<Link to="/operations/dashboard">← Back to Dashboard</Link>}
        />
        <LoadingState description="Loading work orders…" />
      </main>
    );
  }

  // ── Error state ───────────────────────────────────────────────────────────
  if (isError && !data) {
    const is403 = error?.status === 403;
    return (
      <main className={styles.page}>
        <PageHeader
          title={metricLabel}
          subtitle={<Link to="/operations/dashboard">← Back to Dashboard</Link>}
        />
        {is403
          ? <ErrorState description="You do not have permission to view this data." />
          : (
            <ErrorState
              description={error?.message ?? 'Unable to load work orders.'}
              onRetry={() => refetch()}
            />
          )
        }
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <PageHeader
        title={metricLabel}
        subtitle={
          <span>
            {windowLabel}
            {' · '}
            <Link to="/operations/dashboard">← Back to Dashboard</Link>
          </span>
        }
      />

      {/* Active filter chips */}
      <div className={styles.chips} role="list" aria-label="Active filters">
        <span className={styles.chipsLabel}>Filters:</span>
        <span className={styles.chip} role="listitem">Window: {windowLabel}</span>
        <span className={styles.chip} role="listitem">Metric: {metricLabel}</span>
        {segmentLabel && (
          <span className={styles.chip} role="listitem">
            Segment: {segmentLabel}
            <button
              className={styles.chipRemove}
              onClick={handleRemoveSegment}
              aria-label={`Remove segment filter: ${segmentLabel}`}
              type="button"
            >
              ×
            </button>
          </span>
        )}
      </div>

      {/* Reconciliation banner */}
      <ReconciliationBanner reconciliation={reconciliation} />

      {/* Degraded notice during background refetch */}
      {isFetching && !isPending && data && (
        <DegradedState description="Refreshing…" />
      )}

      {/* Data table */}
      {rows.length === 0 ? (
        <EmptyState
          title="No work orders found"
          description="No work orders match the selected filters. Try adjusting the window or segment on the dashboard."
        />
      ) : (
        <>
          <DataTable
            columns={COLUMNS}
            data={rows}
            rowKey={(row) => row.id}
            sort={currentSort}
            onSort={handleSort}
            caption={`${metricLabel} — ${windowLabel}${segmentLabel ? ` — ${segmentLabel}` : ''}`}
          />

          {/* Pagination */}
          {pageMeta && pageMeta.totalPages > 1 && (
            <nav className={styles.pagination} aria-label="Page navigation">
              <button
                className={styles.pageBtn}
                disabled={page === 0}
                onClick={() => setParams((p) => { const n = new URLSearchParams(p); n.set('page', String(page - 1)); return n; })}
                type="button"
                aria-label="Previous page"
              >
                ‹ Previous
              </button>
              <span className={styles.pageInfo} aria-live="polite">
                Page {pageMeta.number + 1} of {pageMeta.totalPages}
                {' '}({pageMeta.totalElements.toLocaleString()} total)
              </span>
              <button
                className={styles.pageBtn}
                disabled={page >= pageMeta.totalPages - 1}
                onClick={() => setParams((p) => { const n = new URLSearchParams(p); n.set('page', String(page + 1)); return n; })}
                type="button"
                aria-label="Next page"
              >
                Next ›
              </button>
            </nav>
          )}
        </>
      )}
    </main>
  );
}
