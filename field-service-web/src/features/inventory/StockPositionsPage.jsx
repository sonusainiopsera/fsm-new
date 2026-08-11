/**
 * @fileoverview StockPositionsPage — warehouse and vehicle stock position view.
 *
 * AC-1: Shared data table with density control, sticky header, right-aligned
 * tabular figures, server pagination (50-item ceiling), allow-listed sorting,
 * deterministic ordering.
 *
 * AC-2: Movement history rendered via MovementHistoryDrawer (no full-dataset fetch).
 *
 * AC-4: 30-second ETag-conditional polling paused on hidden tab; degraded state
 * when as-of exceeds 60-second budget (BR-15).
 *
 * AC-9: Role-based hiding of transfer / adjustment / cross-location controls is a
 * USABILITY affordance only. Authorization is always server-enforced.
 */
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../app/AuthContext.js'
import { PageHeader, DataTable, DensityToggle } from '../../components/index.js'
import { LoadingState, ErrorState, EmptyState, DegradedState } from '../../components/index.js'
import { listStockPositions } from '../../api/inventory.js'
import { MovementHistoryDrawer } from './MovementHistoryDrawer.jsx'
import { evaluateFreshness, isDegraded, formatStaleDuration } from '../../lib/freshness.js'
import { DASHBOARD_INTERVAL } from '../../api/useConditionalQuery.js'

const ALLOWED_SORT_FIELDS = ['partNumber', 'locationName', 'quantityOnHand', 'locationType']

/** @type {import('../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'partNumber', header: 'Part Number', sortable: true },
  { key: 'partDescription', header: 'Description' },
  { key: 'locationName', header: 'Location', sortable: true },
  { key: 'locationType', header: 'Type', sortable: true, render: v => v === 'VEHICLE' ? 'Vehicle' : 'Warehouse' },
  { key: 'quantityOnHand', header: 'On Hand', numeric: true, sortable: true },
  { key: 'reorderPoint', header: 'Reorder Pt', numeric: true, render: v => v ?? '—' },
]

export default function StockPositionsPage() {
  const { roles } = useAuth()
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState(/** @type {string | undefined} */ (undefined))
  const [selectedRow, setSelectedRow] = useState(/** @type {Record<string, unknown> | null} */ (null))
  const [drawerOpen, setDrawerOpen] = useState(false)

  // NOTE: Role-based hiding of transfer and adjustment controls is a USABILITY
  // affordance only. The server enforces authorization for all writes.
  const canTransfer = roles.some(r => ['DISPATCHER', 'MANAGER', 'ADMIN'].includes(r))

  const queryKey = ['inventory', 'stock', { page, sort }]
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey,
    queryFn: ({ signal }) => listStockPositions({ page, size: 50, sort, signal }),
    refetchInterval: DASHBOARD_INTERVAL,
    refetchIntervalInBackground: false,
    placeholderData: (prev) => prev,
  })

  const rows = data?.data ?? []
  const pageInfo = data?.page
  const asOf = rows.length > 0 ? rows[0].asOf : null
  const freshness = evaluateFreshness(asOf)

  function handleRowClick(row) {
    setSelectedRow(row)
    setDrawerOpen(true)
  }

  function handleSortChange(field) {
    if (!ALLOWED_SORT_FIELDS.includes(field)) return
    setSort(prev => {
      if (!prev?.startsWith(field)) return `${field}:ASC`
      if (prev === `${field}:ASC`) return `${field}:DESC`
      return undefined
    })
  }

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader
        title="Stock Positions"
        subtitle={pageInfo ? `${pageInfo.totalElements} position${pageInfo.totalElements !== 1 ? 's' : ''}` : ''}
        actions={
          <div style={{ display: 'flex', gap: 'var(--token-space-2)', alignItems: 'center' }}>
            <DensityToggle />
            {/* Transfer button hidden for TECHNICIAN/CUSTOMER — usability only; server enforces auth */}
            {canTransfer && (
              <button
                type="button"
                disabled
                title="Stock transfer (not yet implemented)"
                style={{ padding: 'var(--token-space-2) var(--token-space-4)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-border-default)', background: 'var(--token-surface-card)', color: 'var(--token-text-secondary)', cursor: 'not-allowed', fontFamily: 'var(--token-family-base)', fontSize: 'var(--token-fs-14)', minHeight: '36px' }}
              >
                Transfer stock
              </button>
            )}
          </div>
        }
      />

      {/* Freshness / as-of indicator (AC-4, BR-15) */}
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
          message={error?.message ?? 'Could not load stock positions.'}
          onRetry={refetch}
        />
      )}
      {!isLoading && !isError && isDegraded(freshness) && rows.length === 0 && (
        <DegradedState message={freshness.humanDescription} onRetry={refetch} />
      )}
      {!isLoading && !isError && rows.length === 0 && !isDegraded(freshness) && (
        <EmptyState message="No stock positions found." />
      )}

      {!isLoading && !isError && rows.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey={r => `${r.locationId}:${r.partId}`}
            selectedRowKey={selectedRow ? `${selectedRow.locationId}:${selectedRow.partId}` : null}
            onRowClick={handleRowClick}
            caption="Stock positions by location and part"
            aria-label="Stock positions by location and part"
          />

          {/* Pagination */}
          {pageInfo && pageInfo.totalPages > 1 && (
            <nav
              aria-label="Stock positions pagination"
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

      {/* Movement history drawer — opens on row click */}
      <MovementHistoryDrawer
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setSelectedRow(null) }}
        partId={selectedRow?.partId}
        partNumber={selectedRow?.partNumber}
        locationId={selectedRow?.locationId}
        locationName={selectedRow?.locationName}
      />
    </div>
  )
}
