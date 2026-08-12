/**
 * @fileoverview DrillDownPage — work orders behind a KPI metric (WO-168).
 *
 * Reads metric, window, segment and pagination from URL search params so
 * views are shareable and deep-linkable without a prior widget fetch.
 *
 * Four named states:
 *   loading   — skeleton / spinner
 *   empty     — 0 results, active filters visible
 *   error     — 403, network error, validation error
 *   success   — DataTable with filter chips + ReconciliationBanner
 *
 * Data classification: Confidential (individual work order records).
 * This page is only rendered within the operations surface which already
 * requires MANAGER / ADMIN / DISPATCHER authentication.
 */

import { useSearchParams, useNavigate } from 'react-router-dom'
import {
  PageHeader,
  DataTable,
  DensityToggle,
  Chip,
  EmptyState,
  LoadingState,
  ErrorState,
} from '../../components/index.js'
import { ReconciliationBanner } from './components/ReconciliationBanner.jsx'
import { useDrillDownWorkOrders } from './api/useDrillDownWorkOrders.js'

// ── Metric label catalogue (mirrors DashboardPage METRIC_LABELS) ─────────────

const METRIC_LABELS = {
  SLA_COMPLIANCE_RATE:   'SLA Compliance',
  SLA_RESOLUTION_MEAN:   'Resolution Time (Mean)',
  SLA_RESOLUTION_MEDIAN: 'Resolution Time (Median)',
  SLA_BREACH_COUNT:      'SLA Breaches',
  FTF_RATE:              'First-Time Fix',
  REPEAT_VISIT_COUNT:    'Repeat Visits',
  BACKLOG_OPEN_COUNT:    'Open Backlog',
  BACKLOG_ON_HOLD_COUNT: 'On-Hold Backlog',
  UTILIZATION_RATE:      'Technician Utilisation',
  JOBS_PER_DAY:          'Jobs / Day',
  WORKLOAD_BALANCE_CV:   'Workload Balance (CV)',
}

const WINDOW_LABELS = {
  SEVEN_DAYS:   'Last 7 days',
  THIRTY_DAYS:  'Last 30 days',
  NINETY_DAYS:  'Last 90 days',
}

// ── Column definitions ────────────────────────────────────────────────────────

/** @type {import('../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  {
    key: 'title',
    header: 'Work Order',
    sortable: false,
    render: (v, row) => (
      <span>
        {v}
        {row.atRisk && (
          <span
            aria-label="At risk — SLA deadline passed"
            style={{ marginLeft: 'var(--token-space-2)', color: 'var(--token-danger-default)' }}
          >
            ⚠
          </span>
        )}
      </span>
    ),
  },
  { key: 'state',    header: 'State',    sortable: false },
  { key: 'priority', header: 'Priority', sortable: false },
  {
    key: 'createdAt',
    header: 'Created',
    sortable: false,
    render: v => v ? new Date(v).toLocaleDateString() : '—',
  },
  {
    key: 'resolutionDeadline',
    header: 'SLA Deadline',
    sortable: false,
    render: v => v ? new Date(v).toLocaleDateString() : '—',
  },
]

// ── DrillDownPage ─────────────────────────────────────────────────────────────

export default function DrillDownPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const metric  = searchParams.get('metric')  ?? ''
  const window_ = searchParams.get('window')  ?? 'THIRTY_DAYS'
  const segment = searchParams.get('segment') ?? 'ALL'
  const page    = parseInt(searchParams.get('page') ?? '0', 10)
  const size    = Math.min(50, parseInt(searchParams.get('size') ?? '20', 10))

  const metricLabel = METRIC_LABELS[metric] ?? metric
  const windowLabel = WINDOW_LABELS[window_] ?? window_

  const { data, isLoading, isError, error, isFetching } = useDrillDownWorkOrders({
    metric,
    window: window_,
    segment: segment === 'ALL' ? undefined : segment,
    page,
    size,
    enabled: Boolean(metric),
  })

  // ── Error or missing metric ───────────────────────────────────────────────

  if (!metric) {
    return (
      <div style={pageStyle}>
        <PageHeader title="Work Order Drill-Down" />
        <ErrorState
          message="No metric specified. Navigate here from a KPI widget."
          onRetry={() => navigate(-1)}
        />
      </div>
    )
  }

  if (isError) {
    const msg = error?.code === 'FORBIDDEN'
      ? 'You do not have permission to view work order details.'
      : error?.message ?? 'Failed to load work orders.'
    return (
      <div style={pageStyle}>
        <PageHeader title={`${metricLabel} — Work Orders`} subtitle={windowLabel} />
        <ErrorState message={msg} onRetry={() => window.location.reload()} />
      </div>
    )
  }

  // ── Loading ───────────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div style={pageStyle}>
        <PageHeader title={`${metricLabel} — Work Orders`} subtitle={windowLabel} />
        <LoadingState />
      </div>
    )
  }

  const rows        = data?.data ?? []
  const pageMeta    = data?.page ?? { number: 0, size, totalElements: 0, totalPages: 0 }
  const links       = data?.links ?? {}
  const recon       = data?.reconciliation

  // ── Active filter chips ───────────────────────────────────────────────────

  const chips = [
    { label: `Metric: ${metricLabel}`, key: 'metric' },
    { label: `Window: ${windowLabel}`, key: 'window' },
    ...(segment && segment !== 'ALL' ? [{ label: `Segment: ${segment}`, key: 'segment' }] : []),
  ]

  function removeFilter(key) {
    const next = new URLSearchParams(searchParams)
    if (key === 'segment') {
      next.delete('segment')
    }
    next.set('page', '0')
    setSearchParams(next, { replace: true })
  }

  function goToPage(newPage) {
    const next = new URLSearchParams(searchParams)
    next.set('page', String(newPage))
    setSearchParams(next, { replace: true })
  }

  return (
    <div style={pageStyle}>
      <PageHeader
        title={`${metricLabel} — Work Orders`}
        subtitle={windowLabel}
      />

      {/* Filter chips row */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 'var(--token-space-2)',
          flexWrap: 'wrap',
          marginBottom: 'var(--token-space-4)',
        }}
        aria-label="Active filters"
      >
        {chips.map(chip => (
          <Chip
            key={chip.key}
            label={chip.label}
            onRemove={chip.key !== 'metric' && chip.key !== 'window'
              ? () => removeFilter(chip.key)
              : undefined}
          />
        ))}
        <DensityToggle />
        {isFetching && (
          <span
            role="status"
            aria-live="polite"
            style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}
          >
            Loading…
          </span>
        )}
      </div>

      {/* Reconciliation banner */}
      {recon && (
        <ReconciliationBanner
          widgetValue={recon.widgetValue}
          widgetDataAsOf={recon.widgetDataAsOf}
          resultCount={recon.resultCount}
          status={recon.status}
          reason={recon.reason}
          metricLabel={metricLabel}
        />
      )}

      {/* Empty state */}
      {rows.length === 0 && (
        <EmptyState
          message={`No work orders found for ${metricLabel} in the ${windowLabel.toLowerCase()}.`}
        />
      )}

      {/* Data table */}
      {rows.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey={row => row.id}
            aria-label={`${metricLabel} work orders`}
            caption={`${pageMeta.totalElements} work order${pageMeta.totalElements !== 1 ? 's' : ''}`}
          />

          {/* Pagination controls */}
          <nav
            aria-label="Pagination"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 'var(--token-space-3)',
              marginTop: 'var(--token-space-4)',
              fontSize: 'var(--token-fs-13)',
            }}
          >
            <button
              onClick={() => goToPage(pageMeta.number - 1)}
              disabled={!links.prev}
              style={pageBtnStyle}
              aria-label="Previous page"
            >
              ← Prev
            </button>
            <span aria-live="polite">
              Page {pageMeta.number + 1} of {pageMeta.totalPages || 1}
            </span>
            <button
              onClick={() => goToPage(pageMeta.number + 1)}
              disabled={!links.next}
              style={pageBtnStyle}
              aria-label="Next page"
            >
              Next →
            </button>
          </nav>
        </>
      )}
    </div>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────

const pageStyle = {
  padding: 'var(--token-space-6)',
  maxWidth: '1400px',
  margin: '0 auto',
  fontFamily: 'var(--token-family-base)',
}

const pageBtnStyle = {
  padding: 'var(--token-space-1) var(--token-space-3)',
  fontSize: 'var(--token-fs-13)',
  fontFamily: 'var(--token-family-base)',
  borderRadius: 'var(--token-radius-control)',
  border: '1px solid var(--token-border-default)',
  background: 'var(--token-surface-card)',
  color: 'var(--token-text-primary)',
  cursor: 'pointer',
}
