/**
 * @fileoverview LowStockPage — active low-stock and stockout alert view.
 *
 * AC-3: Every indicator carries a text label, an icon, AND a distinct shape
 * so meaning never depends on colour alone (BR-32, BR-34).
 *
 * AC-4: 30-second ETag-conditional polling paused on hidden tab.
 * Degraded state when as-of exceeds 60-second budget (BR-15).
 *
 * NOTE: Role-based control hiding (transfer, cross-location views) is a
 * USABILITY affordance only. Authorization is always server-enforced.
 */
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../app/AuthContext.js'
import { PageHeader, DataTable, DensityToggle } from '../../components/index.js'
import { LoadingState, ErrorState, EmptyState, DegradedState } from '../../components/index.js'
import { listAlerts } from '../../api/inventory.js'
import { evaluateFreshness, isDegraded, formatStaleDuration } from '../../lib/freshness.js'
import { DASHBOARD_INTERVAL } from '../../api/useConditionalQuery.js'

/**
 * Alert indicator with text label, icon, AND shape — never colour-only (AC-3, BR-32, BR-34).
 * Passes contrast in both light and dark appearances.
 *
 * @param {{ alertType: 'LOW_STOCK' | 'STOCKOUT' }} props
 */
function AlertIndicator({ alertType }) {
  const isStockout = alertType === 'STOCKOUT'
  return (
    <span
      role="img"
      aria-label={isStockout ? 'Stockout — zero units available' : 'Low stock — below reorder point'}
      title={isStockout ? 'Stockout' : 'Low stock'}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 'var(--token-space-1)',
        padding: '2px var(--token-space-2)',
        borderRadius: isStockout ? '0' : 'var(--token-radius-control)',
        // Shape: stockout = square border, low-stock = rounded
        border: isStockout
          ? '2px solid var(--token-danger-default)'
          : '2px solid var(--token-warning-default)',
        // Text + icon — meaning is never carried by colour alone
        color: isStockout ? 'var(--token-danger-default)' : 'var(--token-warning-default)',
        fontSize: 'var(--token-fs-12)',
        fontWeight: 700,
        whiteSpace: 'nowrap',
      }}
    >
      {/* Icon: different symbol per type */}
      <span aria-hidden="true">{isStockout ? '✕' : '▼'}</span>
      {/* Text label */}
      <span>{isStockout ? 'Stockout' : 'Low stock'}</span>
    </span>
  )
}

/** @type {import('../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'partNumber', header: 'Part Number', sortable: true },
  { key: 'partDescription', header: 'Description' },
  { key: 'locationName', header: 'Location', sortable: true },
  { key: 'locationType', header: 'Type', render: v => v === 'VEHICLE' ? 'Vehicle' : 'Warehouse' },
  {
    key: 'alertType',
    header: 'Status',
    render: (v) => <AlertIndicator alertType={v} />,
  },
  { key: 'quantityOnHand', header: 'On Hand', numeric: true, sortable: true },
  { key: 'reorderPoint', header: 'Reorder Pt', numeric: true },
  { key: 'raisedAt', header: 'Raised', render: v => new Date(v).toLocaleString(), sortable: true },
]

export default function LowStockPage() {
  const { roles } = useAuth()
  const [page, setPage] = useState(0)

  // NOTE: Technicians see only their vehicle location (enforced server-side).
  // Showing all alerts to DISPATCHER/MANAGER is a usability affordance only —
  // the server enforces row-scope access for all reads.
  const isTechnician = roles.includes('TECHNICIAN')

  const queryKey = ['inventory', 'alerts', { page }]
  const { data, isLoading, isError, error, refetch, dataUpdatedAt } = useQuery({
    queryKey,
    queryFn: ({ signal }) => listAlerts({ page, size: 50, signal }),
    refetchInterval: DASHBOARD_INTERVAL,
    refetchIntervalInBackground: false,
    placeholderData: (prev) => prev,
  })

  const rows = data?.data ?? []
  const pageInfo = data?.page
  // Use the most recent occurredAt / raisedAt across rows as the freshness proxy
  const asOf = rows.length > 0
    ? rows.reduce((latest, r) => r.raisedAt > latest ? r.raisedAt : latest, rows[0].raisedAt)
    : null
  const freshness = evaluateFreshness(asOf)

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1200px', margin: '0 auto' }}>
      <PageHeader
        title="Low-Stock Alerts"
        subtitle={`${rows.length} active alert${rows.length !== 1 ? 's' : ''}`}
        actions={<DensityToggle />}
      />

      {/* Freshness / as-of indicator — always shown when data is present (AC-4, BR-15) */}
      {asOf && (
        <p
          aria-live="polite"
          style={{
            fontSize: 'var(--token-fs-12)',
            color: isDegraded(freshness) ? 'var(--token-warning-default)' : 'var(--token-text-secondary)',
            margin: '0 0 var(--token-space-4)',
          }}
        >
          {isDegraded(freshness)
            ? `⚠ ${freshness.humanDescription}`
            : `As of ${new Date(asOf).toLocaleTimeString()}`}
        </p>
      )}

      {isLoading && <LoadingState />}
      {isError && (
        <ErrorState
          message={error?.message ?? 'Could not load alerts.'}
          onRetry={refetch}
        />
      )}
      {!isLoading && !isError && isDegraded(freshness) && rows.length === 0 && (
        <DegradedState message={freshness.humanDescription} onRetry={refetch} />
      )}
      {!isLoading && !isError && rows.length === 0 && !isDegraded(freshness) && (
        <EmptyState message="No active low-stock or stockout alerts." />
      )}

      {!isLoading && !isError && rows.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey={r => r.alertId}
            caption="Active low-stock and stockout alerts"
            aria-label="Active low-stock and stockout alerts"
          />

          {/* Pagination */}
          {pageInfo && pageInfo.totalPages > 1 && (
            <nav
              aria-label="Alerts pagination"
              style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 'var(--token-space-4)' }}
            >
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage(p => p - 1)}
                style={{ padding: 'var(--token-space-2) var(--token-space-4)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-border-default)', background: 'var(--token-surface-card)', cursor: page === 0 ? 'not-allowed' : 'pointer', minHeight: '44px', color: 'var(--token-text-primary)', fontFamily: 'var(--token-family-base)', fontSize: 'var(--token-fs-14)' }}
              >
                ← Previous
              </button>
              <span style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}>
                Page {page + 1} of {pageInfo.totalPages}
              </span>
              <button
                type="button"
                disabled={page >= pageInfo.totalPages - 1}
                onClick={() => setPage(p => p + 1)}
                style={{ padding: 'var(--token-space-2) var(--token-space-4)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-border-default)', background: 'var(--token-surface-card)', cursor: page >= pageInfo.totalPages - 1 ? 'not-allowed' : 'pointer', minHeight: '44px', color: 'var(--token-text-primary)', fontFamily: 'var(--token-family-base)', fontSize: 'var(--token-fs-14)' }}
              >
                Next →
              </button>
            </nav>
          )}
        </>
      )}
    </div>
  )
}
