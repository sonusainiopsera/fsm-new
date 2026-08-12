/**
 * @fileoverview WorkOrderBoardPage — Dispatcher work order board.
 *
 * AC-2: URL is the source of truth for filters, sort, page, and open drawer id.
 *       Back/forward navigation restores the full board state.
 * AC-3: 30-second conditional GET polling via useWorkOrderSearch.
 * AC-4: DetailDrawer is deep-linkable (drawerWoId in URL), closes on Escape or
 *       overlay click, restores focus to the invoking row.
 * AC-5: Lifecycle actions rendered strictly from server legalNextEvents — no
 *       client-side lifecycle rule duplication.
 * AC-10: Loading, empty, error, and permission-denied each have a designed treatment.
 */
import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  PageHeader,
  LoadingState,
  EmptyState,
  ErrorState,
  PermissionDeniedState,
  Button,
} from '../../components/index.js'
import { parsePagedEnvelope } from '../../api/pagination.js'
import { FilterBar } from './components/FilterBar.jsx'
import { WorkOrderTable } from './components/WorkOrderTable.jsx'
import { WorkOrderDetailDrawer } from './components/DetailDrawer.jsx'
import { useWorkOrderSearch } from './api/useWorkOrderSearch.js'
import { CreateWorkOrderModal } from './components/CreateWorkOrderModal.jsx'

/** URL param names — single source of truth to avoid typos. */
const PARAM = {
  PAGE: 'page',
  SORT: 'sort',
  DRAWER: 'wo',
  STATE: 'state',
  PRIORITY: 'priority',
  AT_RISK: 'atRisk',
  DATE_FROM: 'dateFrom',
  DATE_TO: 'dateTo',
  TECHNICIAN: 'technicianId',
  CUSTOMER: 'customerId',
}

/** Number of rows per page for the board. */
const PAGE_SIZE = 20

/** Parses URL search params into the filter/sort/page/drawer state the page needs. */
function parseUrlState(params) {
  const states = params.getAll(PARAM.STATE).flatMap(v => v.split(',').filter(Boolean))
  const priorities = params.getAll(PARAM.PRIORITY).flatMap(v => v.split(',').filter(Boolean))
  return {
    page: Math.max(0, parseInt(params.get(PARAM.PAGE) ?? '0', 10) || 0),
    sort: params.get(PARAM.SORT) ?? undefined,
    drawerId: params.get(PARAM.DRAWER) ?? null,
    filters: {
      states: states.length ? states : undefined,
      priorities: priorities.length ? priorities : undefined,
      technicianId: params.get(PARAM.TECHNICIAN) ?? undefined,
      customerId: params.get(PARAM.CUSTOMER) ?? undefined,
      atRisk: params.get(PARAM.AT_RISK) === 'true' ? true : undefined,
      dateFrom: params.get(PARAM.DATE_FROM) ?? undefined,
      dateTo: params.get(PARAM.DATE_TO) ?? undefined,
    },
  }
}

/** Writes filter state into URL search params. Does not change page/sort/drawer. */
function filtersToParams(filters, existing) {
  const next = new URLSearchParams(existing)
  // Always reset to page 0 on filter change
  next.set(PARAM.PAGE, '0')
  // Clear old filter params
  next.delete(PARAM.STATE)
  next.delete(PARAM.PRIORITY)
  next.delete(PARAM.TECHNICIAN)
  next.delete(PARAM.CUSTOMER)
  next.delete(PARAM.AT_RISK)
  next.delete(PARAM.DATE_FROM)
  next.delete(PARAM.DATE_TO)

  if (filters.states?.length) next.set(PARAM.STATE, filters.states.join(','))
  if (filters.priorities?.length) next.set(PARAM.PRIORITY, filters.priorities.join(','))
  if (filters.technicianId) next.set(PARAM.TECHNICIAN, filters.technicianId)
  if (filters.customerId) next.set(PARAM.CUSTOMER, filters.customerId)
  if (filters.atRisk) next.set(PARAM.AT_RISK, 'true')
  if (filters.dateFrom) next.set(PARAM.DATE_FROM, filters.dateFrom)
  if (filters.dateTo) next.set(PARAM.DATE_TO, filters.dateTo)
  return next
}

export default function WorkOrderBoardPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { page, sort, drawerId, filters } = parseUrlState(searchParams)
  const [createModalOpen, setCreateModalOpen] = useState(false)

  const { data, isLoading, isFetching, isError, error } = useWorkOrderSearch({
    page,
    size: PAGE_SIZE,
    sort,
    filters,
    enabled: true,
  })

  const envelope = data ? parsePagedEnvelope(data) : null
  const rows = envelope?.data ?? []
  const pageMeta = envelope?.page

  // The currently-open work order row (looked up from the loaded page data)
  const drawerRow = drawerId ? rows.find(r => r.id === drawerId) ?? null : null

  const handleFiltersChange = useCallback((nextFilters) => {
    setSearchParams(prev => filtersToParams(nextFilters, prev), { replace: true })
  }, [setSearchParams])

  const handleSort = useCallback((field) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      const currentSort = next.get(PARAM.SORT)
      const [currentField, currentDir] = (currentSort ?? '').split(':')
      const nextDir = currentField === field && currentDir !== 'DESC' ? 'DESC' : 'ASC'
      next.set(PARAM.SORT, `${field}:${nextDir}`)
      next.set(PARAM.PAGE, '0')
      return next
    }, { replace: true })
  }, [setSearchParams])

  const handleRowSelect = useCallback((row) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      next.set(PARAM.DRAWER, row.id)
      return next
    }, { replace: false })
  }, [setSearchParams])

  const handleDrawerClose = useCallback(() => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      next.delete(PARAM.DRAWER)
      return next
    }, { replace: false })
  }, [setSearchParams])

  const handlePage = useCallback((nextPage) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      next.set(PARAM.PAGE, String(nextPage))
      return next
    }, { replace: true })
  }, [setSearchParams])

  const isPermissionDenied = isError && error?.status === 403

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
        overflow: 'hidden',
        background: 'var(--token-surface-base)',
      }}
    >
      <PageHeader
        title="Work Orders"
        description="Dispatcher board — all open and recently closed work orders."
      >
        {isFetching && !isLoading && (
          <span
            aria-live="polite"
            aria-label="Refreshing"
            style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}
          >
            Refreshing…
          </span>
        )}
        <Button
          id="create-work-order-btn"
          variant="primary"
          onClick={() => setCreateModalOpen(true)}
        >
          + Create Work Order
        </Button>
      </PageHeader>

      <FilterBar filters={filters} onChange={handleFiltersChange} />

      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', position: 'relative' }}>
        {isLoading && (
          <LoadingState message="Loading work orders…" />
        )}

        {!isLoading && isPermissionDenied && (
          <PermissionDeniedState message="You don't have permission to view work orders." />
        )}

        {!isLoading && isError && !isPermissionDenied && (
          <ErrorState
            message="Failed to load work orders."
            detail={error?.message}
          />
        )}

        {!isLoading && !isError && rows.length === 0 && (
          <EmptyState message="No work orders match the current filters." />
        )}

        {!isLoading && !isError && rows.length > 0 && (
          <WorkOrderTable
            rows={rows}
            selectedId={drawerId}
            sort={sort}
            onSort={handleSort}
            onRowSelect={handleRowSelect}
          />
        )}
      </div>

      {/* Pagination controls */}
      {pageMeta && pageMeta.totalPages > 1 && (
        <nav
          aria-label="Work order board pagination"
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: 'var(--token-space-3) var(--token-space-4)',
            borderTop: '1px solid var(--token-border-default)',
            background: 'var(--token-surface-card)',
            fontSize: 'var(--token-fs-13)',
          }}
        >
          <span style={{ color: 'var(--token-text-secondary)' }}>
            Page {pageMeta.page + 1} of {pageMeta.totalPages}
            {pageMeta.totalElements != null && ` · ${pageMeta.totalElements.toLocaleString()} total`}
          </span>
          <div style={{ display: 'flex', gap: 'var(--token-space-2)' }}>
            <button
              type="button"
              onClick={() => handlePage(page - 1)}
              disabled={!pageMeta.hasPrev}
              aria-label="Previous page"
              style={{
                padding: 'var(--token-space-1) var(--token-space-3)',
                fontSize: 'var(--token-fs-13)',
                border: '1px solid var(--token-border-default)',
                borderRadius: 'var(--token-radius-sm)',
                background: 'var(--token-surface-card)',
                cursor: pageMeta.hasPrev ? 'pointer' : 'not-allowed',
                opacity: pageMeta.hasPrev ? 1 : 0.4,
              }}
            >
              ← Previous
            </button>
            <button
              type="button"
              onClick={() => handlePage(page + 1)}
              disabled={!pageMeta.hasNext}
              aria-label="Next page"
              style={{
                padding: 'var(--token-space-1) var(--token-space-3)',
                fontSize: 'var(--token-fs-13)',
                border: '1px solid var(--token-border-default)',
                borderRadius: 'var(--token-radius-sm)',
                background: 'var(--token-surface-card)',
                cursor: pageMeta.hasNext ? 'pointer' : 'not-allowed',
                opacity: pageMeta.hasNext ? 1 : 0.4,
              }}
            >
              Next →
            </button>
          </div>
        </nav>
      )}

      {/* Detail drawer — AC-4: deep-linkable, focus-trapped, Escape to close */}
      <WorkOrderDetailDrawer
        workOrder={drawerRow}
        open={!!drawerId}
        onClose={handleDrawerClose}
      />

      {/* Create Work Order Modal — AC-1 */}
      <CreateWorkOrderModal
        open={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
      />
    </div>
  )
}
