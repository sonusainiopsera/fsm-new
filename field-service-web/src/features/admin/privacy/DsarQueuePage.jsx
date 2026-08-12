/**
 * @fileoverview DSAR Queue — paginated list with countdown and state filter.
 *
 * AC-5: Remaining-days countdown computed from server-supplied dueAt and
 *       at-risk flag. At-risk treatment applied ONLY when server flags it.
 * AC-5: Overdue rendered distinctly (negative remaining days).
 * AC-1: Role guard — PRIVACY_ADMIN or ADMIN only.
 *
 * @module features/admin/privacy/DsarQueuePage
 */
import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../../app/AuthContext.js'
import {
  PageHeader, DataTable,
  LoadingState, ErrorState, EmptyState, PermissionDeniedState,
} from '../../../components/index.js'
import { Pagination } from '../../../shared/components/Pagination.jsx'
import { useUrlPageState } from '../../../shared/hooks/useUrlPageState.js'
import { useDsarRequests, computeRemainingDays } from '../../privacy/hooks/useDsarRequests.js'

const DSAR_STATES = [
  'RECEIVED', 'IDENTITY_PENDING', 'VERIFIED', 'IN_PROGRESS',
  'FULFILLED', 'REJECTED', 'WITHDRAWN',
]

const ALLOWED_SORT = ['submittedAt', 'dueAt', 'state', 'requestType']

/** @param {{ dueAt: string, atRisk: boolean }} row */
function CountdownCell({ dueAt, atRisk }) {
  const days = computeRemainingDays(dueAt)
  const isOverdue = days < 0

  if (isOverdue) {
    return (
      <span
        aria-label={`Overdue by ${Math.abs(days)} days`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 'var(--token-space-1)',
          color: 'var(--token-danger-emphasis)',
          fontWeight: 600,
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        ⚠ Overdue ({Math.abs(days)}d)
      </span>
    )
  }

  if (atRisk) {
    return (
      <span
        aria-label={`At risk — ${days} days remaining`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 'var(--token-space-1)',
          color: 'var(--token-warning-emphasis)',
          fontWeight: 600,
          fontVariantNumeric: 'tabular-nums',
          background: 'var(--token-warning-subtle)',
          padding: 'var(--token-space-1) var(--token-space-2)',
          borderRadius: 'var(--token-radius-control)',
          border: '1px solid var(--token-warning-default)',
        }}
      >
        △ {days}d
      </span>
    )
  }

  return (
    <span
      aria-label={`${days} days remaining`}
      style={{ fontVariantNumeric: 'tabular-nums' }}
    >
      {days}d
    </span>
  )
}

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'requestType', header: 'Type', sortable: true },
  { key: 'subjectType', header: 'Subject Type' },
  { key: 'subjectId', header: 'Subject ID', render: v => <code style={{ fontVariantNumeric: 'tabular-nums', fontSize: 'var(--token-fs-13)' }}>{v}</code> },
  { key: 'state', header: 'State', sortable: true },
  {
    key: 'submittedAt',
    header: 'Submitted',
    sortable: true,
    render: v => new Date(v).toLocaleDateString(),
  },
  {
    key: 'dueAt',
    header: 'Due',
    sortable: true,
    render: (_v, row) => <CountdownCell dueAt={row.dueAt} atRisk={row.atRisk} />,
  },
]

export default function DsarQueuePage() {
  const { roles } = useAuth()
  const canView = roles.some(r => ['PRIVACY_ADMIN', 'ADMIN'].includes(r))

  if (!canView) {
    return <PermissionDeniedState />
  }

  return <DsarQueueContent />
}

function DsarQueueContent() {
  const navigate = useNavigate()
  const { state, setPage, setSort, setFilter } = useUrlPageState({ defaultSort: 'dueAt:ASC' })

  const stateFilter = state.filter

  const { rows, page, isLoading, isFetching, isError, error, refetch } = useDsarRequests({
    page: state.page,
    size: state.size,
    sort: state.sort,
    state: stateFilter,
  })

  function handleSortChange(field) {
    if (!ALLOWED_SORT.includes(field)) return
    setSort(state.sort?.startsWith(field)
      ? (state.sort === `${field}:ASC` ? `${field}:DESC` : undefined)
      : `${field}:ASC`)
  }

  const handleRowClick = useCallback((row) => {
    navigate(`/admin/privacy/dsar/${row.id}`)
  }, [navigate])

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader title="Data Subject Requests" />

      {/* State filter */}
      <div
        role="group"
        aria-label="Filter by request state"
        style={{
          display: 'flex',
          gap: 'var(--token-space-2)',
          flexWrap: 'wrap',
          marginBottom: 'var(--token-space-4)',
        }}
      >
        <button
          type="button"
          aria-pressed={!stateFilter}
          onClick={() => setFilter(undefined)}
          style={filterButtonStyle(!stateFilter)}
        >
          All
        </button>
        {DSAR_STATES.map(s => (
          <button
            key={s}
            type="button"
            aria-pressed={stateFilter === s}
            onClick={() => setFilter(s === stateFilter ? undefined : s)}
            style={filterButtonStyle(stateFilter === s)}
          >
            {s.replace(/_/g, ' ')}
          </button>
        ))}
      </div>

      {/* Refetching indicator */}
      {isFetching && !isLoading && (
        <div aria-live="polite" aria-label="Updating results" role="status" style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', marginBottom: 'var(--token-space-2)' }}>
          Updating…
        </div>
      )}

      {isLoading && <LoadingState />}
      {isError && <ErrorState onRetry={refetch} message={error?.message} />}
      {!isLoading && !isError && rows.length === 0 && (
        <EmptyState message={stateFilter ? `No ${stateFilter.replace(/_/g, ' ').toLowerCase()} requests.` : 'No data subject requests found.'} />
      )}

      {!isLoading && !isError && rows.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey={r => r.id}
            onRowClick={handleRowClick}
            caption="Data subject requests queue"
            aria-label="DSAR queue"
            onSortChange={handleSortChange}
            sortKey={state.sort?.split(':')[0]}
            sortDir={state.sort?.split(':')[1]}
          />
          <Pagination page={page} onPageChange={setPage} />
        </>
      )}
    </div>
  )
}

/** @param {boolean} active */
function filterButtonStyle(active) {
  return {
    minHeight: '44px',
    padding: '0 var(--token-space-3)',
    border: `1px solid ${active ? 'var(--token-accent-400)' : 'var(--token-border-default)'}`,
    borderRadius: 'var(--token-radius-control)',
    background: active ? 'var(--token-accent-50)' : 'var(--token-surface-default)',
    color: active ? 'var(--token-accent-700)' : 'var(--token-text-primary)',
    fontFamily: 'var(--token-family-base)',
    fontSize: 'var(--token-fs-14)',
    cursor: 'pointer',
    fontWeight: active ? 600 : 400,
  }
}
