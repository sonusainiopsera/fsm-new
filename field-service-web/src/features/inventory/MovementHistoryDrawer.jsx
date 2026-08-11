/**
 * @fileoverview MovementHistoryDrawer — paginated movement history for a part/location.
 *
 * Renders the WO-054 movements API inside a DetailDrawer.
 * Server-paginated (50-item ceiling). No client-side full-dataset fetching (AC-2).
 * 30-second ETag-conditional polling paused on hidden tab (AC-4).
 * Degraded state when as-of exceeds 60-second budget (AC-4 / BR-15).
 */
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { DetailDrawer } from '../../components/index.js'
import { LoadingState, ErrorState, EmptyState, DegradedState } from '../../components/index.js'
import { listMovements } from '../../api/inventory.js'
import { evaluateFreshness, isDegraded, formatStaleDuration } from '../../lib/freshness.js'
import { DASHBOARD_INTERVAL } from '../../api/useConditionalQuery.js'

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   partId?: string,
 *   partNumber?: string,
 *   locationId?: string,
 *   locationName?: string
 * }} props
 */
export function MovementHistoryDrawer({ open, onClose, partId, partNumber, locationId, locationName }) {
  const [page, setPage] = useState(0)

  const queryKey = ['inventory', 'movements', { partId, locationId, page }]

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey,
    queryFn: ({ signal }) => listMovements({ partId, locationId, page, size: 50, signal }),
    enabled: open,
    refetchInterval: DASHBOARD_INTERVAL,
    refetchIntervalInBackground: false, // paused on hidden tab
    placeholderData: (prev) => prev,
  })

  const title = [
    partNumber && `Part: ${partNumber}`,
    locationName && `Location: ${locationName}`,
    'Movement History',
  ].filter(Boolean).join(' — ')

  const rows = data?.data ?? []
  const pageInfo = data?.page
  const asOf = rows[0]?.occurredAt
  const freshness = evaluateFreshness(asOf)

  function formatDelta(delta) {
    return delta > 0 ? `+${delta}` : String(delta)
  }

  return (
    <DetailDrawer open={open} onClose={onClose} title={title} width="560px">
      {isLoading && <LoadingState />}
      {isError && (
        <ErrorState
          message={error?.message ?? 'Could not load movement history.'}
          onRetry={refetch}
        />
      )}
      {!isLoading && !isError && isDegraded(freshness) && (
        <DegradedState
          message={freshness.humanDescription}
          onRetry={refetch}
        />
      )}
      {!isLoading && !isError && rows.length === 0 && (
        <EmptyState message="No movement history for this selection." />
      )}
      {!isLoading && !isError && rows.length > 0 && (
        <div>
          {isDegraded(freshness) && (
            <p
              role="status"
              aria-live="polite"
              style={{
                fontSize: 'var(--token-fs-12)',
                color: 'var(--token-warning-default)',
                margin: '0 0 var(--token-space-4)',
                padding: 'var(--token-space-2) var(--token-space-3)',
                border: '1px solid var(--token-warning-default)',
                borderRadius: 'var(--token-radius-control)',
              }}
            >
              ⚠ Stale data — last updated {formatStaleDuration(freshness.stalenessMs)}
            </p>
          )}

          <table
            aria-label="Movement history"
            style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--token-fs-14)' }}
          >
            <thead style={{ position: 'sticky', top: 0, background: 'var(--token-surface-overlay)' }}>
              <tr>
                {['Date', 'Type', 'Part', 'Δ Qty', 'Balance', 'Work Order'].map(h => (
                  <th
                    key={h}
                    scope="col"
                    style={{
                      padding: 'var(--token-space-2) var(--token-space-3)',
                      textAlign: h === 'Δ Qty' || h === 'Balance' ? 'right' : 'left',
                      fontVariantNumeric: h === 'Δ Qty' || h === 'Balance' ? 'var(--token-numeric)' : undefined,
                      fontSize: 'var(--token-fs-12)',
                      color: 'var(--token-text-secondary)',
                      fontWeight: 600,
                      borderBottom: 'var(--token-elevation-border)',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map(row => (
                <tr key={row.ledgerId} style={{ borderBottom: 'var(--token-elevation-border)' }}>
                  <td style={{ padding: 'var(--token-space-2) var(--token-space-3)', fontSize: 'var(--token-fs-13)', whiteSpace: 'nowrap' }}>
                    {new Date(row.occurredAt).toLocaleString()}
                  </td>
                  <td style={{ padding: 'var(--token-space-2) var(--token-space-3)' }}>
                    {row.movementType.replace(/_/g, ' ')}
                  </td>
                  <td style={{ padding: 'var(--token-space-2) var(--token-space-3)', maxWidth: '160px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={row.partNumber}>
                    {row.partNumber}
                  </td>
                  <td style={{ padding: 'var(--token-space-2) var(--token-space-3)', textAlign: 'right', fontVariantNumeric: 'var(--token-numeric)', color: row.delta < 0 ? 'var(--token-danger-default)' : 'var(--token-success-default)', fontWeight: 600 }}>
                    {formatDelta(row.delta)}
                  </td>
                  <td style={{ padding: 'var(--token-space-2) var(--token-space-3)', textAlign: 'right', fontVariantNumeric: 'var(--token-numeric)' }}>
                    {row.balanceAfter}
                  </td>
                  <td style={{ padding: 'var(--token-space-2) var(--token-space-3)', fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>
                    {row.workOrderId ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Pagination */}
          {pageInfo && pageInfo.totalPages > 1 && (
            <nav
              aria-label="Movement history pagination"
              style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 'var(--token-space-4)', gap: 'var(--token-space-2)' }}
            >
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage(p => p - 1)}
                style={{ padding: 'var(--token-space-2) var(--token-space-4)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-border-default)', background: 'var(--token-surface-card)', cursor: page === 0 ? 'not-allowed' : 'pointer', minHeight: '44px', minWidth: '44px', color: 'var(--token-text-primary)', fontFamily: 'var(--token-family-base)', fontSize: 'var(--token-fs-14)' }}
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
                style={{ padding: 'var(--token-space-2) var(--token-space-4)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-border-default)', background: 'var(--token-surface-card)', cursor: page >= pageInfo.totalPages - 1 ? 'not-allowed' : 'pointer', minHeight: '44px', minWidth: '44px', color: 'var(--token-text-primary)', fontFamily: 'var(--token-family-base)', fontSize: 'var(--token-fs-14)' }}
              >
                Next →
              </button>
            </nav>
          )}
        </div>
      )}
    </DetailDrawer>
  )
}
