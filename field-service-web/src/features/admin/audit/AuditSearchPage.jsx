/**
 * @fileoverview Audit trail search screen — filter bar, dense results table, diff drawer.
 *
 * SECURITY: Role filtering in the UI is a USABILITY affordance only.
 * Server-side authorization (403) is the actual control.
 *
 * Accessibility: fully keyboard operable, 2px accent focus ring, reduced-motion safe,
 * named empty/loading/degraded/permission-denied/error states.
 *
 * @module features/admin/audit/AuditSearchPage
 */
import { useState, useCallback, useReducer } from 'react'
import {
  PageHeader, DataTable, DetailDrawer, Button,
  LoadingState, ErrorState, EmptyState,
} from '../../../components/index.js'
import { Pagination } from '../../../shared/components/Pagination.jsx'
import { useAuth } from '../../../app/AuthContext.js'
import { useAuditRevisions, fetchRevisionDiff, useRequestExport } from './useAuditRevisions.js'

// ── Allow-listed entity types ─────────────────────────────────────────────────
// Must mirror AuditEntityMetadata on the backend.
const ENTITY_TYPES = [
  '', 'WorkOrder', 'AppUser', 'Site', 'Assignment',
  'SlaPolicy', 'Customer', 'Asset', 'Technician',
]

// ── Table columns ─────────────────────────────────────────────────────────────

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  {
    key: 'revisionNumber',
    header: 'Rev #',
    sortable: false,
    style: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  },
  {
    key: 'revisionTimestamp',
    header: 'Timestamp',
    render: v => v ? new Date(v).toISOString().replace('T', ' ').slice(0, 19) + ' UTC' : '—',
  },
  { key: 'entityType',  header: 'Entity Type' },
  { key: 'entityId',   header: 'Entity ID',
    render: v => v ? <code style={{ fontSize: 'var(--fs-xs)' }}>{v}</code> : '—' },
  { key: 'changeType', header: 'Change',
    render: v => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v ?? '—'}</span> },
  { key: 'actor',      header: 'Actor',
    render: v => v ?? <span style={{ color: 'var(--color-text-subtle)' }}>system</span> },
]

// ── Filter state ──────────────────────────────────────────────────────────────

function filterReducer(state, action) {
  switch (action.type) {
    case 'set': return { ...state, [action.key]: action.value }
    case 'reset': return {}
    default: return state
  }
}

// ── Main component ────────────────────────────────────────────────────────────

/**
 * Audit trail search page with filter bar, paginated results and diff drawer.
 *
 * States: empty, loading, degraded (stale), permission-denied, error.
 *
 * @returns {import('react').JSX.Element}
 */
export default function AuditSearchPage() {
  const { roles } = useAuth()

  // Permission guard — server enforcement is the actual control
  const canAccess = roles.some(r => ['ADMIN', 'COMPLIANCE_REVIEWER'].includes(r))

  const [filters, dispatch] = useReducer(filterReducer, {})
  const [page, setPage] = useState(0)
  const [drawerRevision, setDrawerRevision] = useState(/** @type {object|null} */ (null))
  const [diffData, setDiffData] = useState(/** @type {object|null} */ (null))
  const [diffLoading, setDiffLoading] = useState(false)
  const [exportResult, setExportResult] = useState(/** @type {object|null} */ (null))

  const exportMutation = useRequestExport()

  const { rows, page: pageMeta, isLoading, isError, error, isFetching } = useAuditRevisions(
    filters, { page, size: 20 }
  )

  const handleFilterChange = useCallback((key, value) => {
    dispatch({ type: 'set', key, value: value || undefined })
    setPage(0)
  }, [])

  const handleRowClick = useCallback(async (row) => {
    setDrawerRevision(row)
    setDiffData(null)
    if (row.entityType && row.entityId) {
      setDiffLoading(true)
      try {
        const detail = await fetchRevisionDiff(row.revisionNumber, row.entityType, row.entityId)
        setDiffData(detail.data ?? detail)
      } catch (e) {
        setDiffData({ error: e.message })
      } finally {
        setDiffLoading(false)
      }
    }
  }, [])

  const handleExport = useCallback(async (format) => {
    try {
      const result = await exportMutation.mutateAsync({ ...filters, format })
      setExportResult(result)
    } catch (e) {
      setExportResult({ error: e.message })
    }
  }, [filters, exportMutation])

  // ── Permission denied state ─────────────────────────────────────────────────
  if (!canAccess) {
    return (
      <main aria-label="Audit Trail">
        <PageHeader title="Audit Trail" />
        <div role="status" aria-live="polite">
          <p style={{ color: 'var(--color-text-danger)', padding: 'var(--space-4)' }}>
            Permission denied — ADMIN or COMPLIANCE_REVIEWER role required.
          </p>
        </div>
      </main>
    )
  }

  return (
    <main aria-label="Audit Trail">
      <PageHeader
        title="Audit Trail"
        actions={
          <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => handleExport('CSV')}
              disabled={exportMutation.isPending}
              aria-label="Export filtered results as CSV"
            >
              {exportMutation.isPending ? 'Exporting…' : 'Export CSV'}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => handleExport('JSON')}
              disabled={exportMutation.isPending}
              aria-label="Export filtered results as JSON"
            >
              Export JSON
            </Button>
          </div>
        }
      />

      {/* ── Filter bar ─────────────────────────────────────────────────────── */}
      <section
        aria-label="Search filters"
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 'var(--space-3)',
          padding: 'var(--space-4)',
          borderBottom: '1px solid var(--color-border-hairline)',
        }}
      >
        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)', fontSize: 'var(--fs-sm)' }}>
          Entity Type
          <select
            value={filters.entityType ?? ''}
            onChange={e => handleFilterChange('entityType', e.target.value)}
            style={{ padding: 'var(--space-1) var(--space-2)', borderRadius: 'var(--radius-sm)' }}
          >
            {ENTITY_TYPES.map(t => (
              <option key={t} value={t}>{t || 'All types'}</option>
            ))}
          </select>
        </label>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)', fontSize: 'var(--fs-sm)' }}>
          Entity ID
          <input
            type="text"
            placeholder="UUID"
            value={filters.entityId ?? ''}
            onChange={e => handleFilterChange('entityId', e.target.value)}
            style={{ padding: 'var(--space-1) var(--space-2)', borderRadius: 'var(--radius-sm)', width: '18ch' }}
          />
        </label>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)', fontSize: 'var(--fs-sm)' }}>
          Actor ID
          <input
            type="text"
            placeholder="Actor user UUID"
            value={filters.actorId ?? ''}
            onChange={e => handleFilterChange('actorId', e.target.value)}
            style={{ padding: 'var(--space-1) var(--space-2)', borderRadius: 'var(--radius-sm)', width: '18ch' }}
          />
        </label>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)', fontSize: 'var(--fs-sm)' }}>
          From
          <input
            type="datetime-local"
            value={filters.from ?? ''}
            onChange={e => handleFilterChange('from', e.target.value ? new Date(e.target.value).toISOString() : '')}
            style={{ padding: 'var(--space-1) var(--space-2)', borderRadius: 'var(--radius-sm)' }}
          />
        </label>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)', fontSize: 'var(--fs-sm)' }}>
          To
          <input
            type="datetime-local"
            value={filters.to ?? ''}
            onChange={e => handleFilterChange('to', e.target.value ? new Date(e.target.value).toISOString() : '')}
            style={{ padding: 'var(--space-1) var(--space-2)', borderRadius: 'var(--radius-sm)' }}
          />
        </label>

        <div style={{ display: 'flex', alignItems: 'flex-end' }}>
          <Button variant="ghost" size="sm" onClick={() => { dispatch({ type: 'reset' }); setPage(0) }}>
            Clear filters
          </Button>
        </div>
      </section>

      {/* ── Staleness indicator ─────────────────────────────────────────────── */}
      {isFetching && !isLoading && (
        <div role="status" aria-live="polite" style={{ padding: 'var(--space-2) var(--space-4)', fontSize: 'var(--fs-sm)', color: 'var(--color-text-subtle)' }}>
          Refreshing results…
        </div>
      )}

      {/* ── Export result ───────────────────────────────────────────────────── */}
      {exportResult && (
        <div
          role="status"
          aria-live="polite"
          style={{ padding: 'var(--space-2) var(--space-4)', fontSize: 'var(--fs-sm)', background: 'var(--color-surface-subtle)' }}
        >
          {exportResult.error
            ? <span style={{ color: 'var(--color-text-danger)' }}>Export failed: {exportResult.error}</span>
            : exportResult.status === 'PENDING'
              ? `Export queued — poll /exports/${exportResult.exportId} for status.`
              : `Export complete (${exportResult.status}).`}
          {' '}
          <Button variant="ghost" size="sm" onClick={() => setExportResult(null)}>Dismiss</Button>
        </div>
      )}

      {/* ── Main content ────────────────────────────────────────────────────── */}
      {isLoading
        ? <LoadingState />
        : isError
          ? <ErrorState message={error?.message ?? 'Failed to load audit revisions'} onRetry={() => {}} />
          : rows.length === 0
            ? <EmptyState message="No audit revisions match the current filters." />
            : (
              <>
                <DataTable
                  columns={COLUMNS}
                  rows={rows}
                  onRowClick={handleRowClick}
                  aria-label="Audit revisions"
                  style={{ '--table-row-height': '40px' }}
                />
                {pageMeta && (
                  <Pagination
                    page={pageMeta.number ?? page}
                    size={pageMeta.size ?? 20}
                    totalElements={pageMeta.totalElements ?? 0}
                    onPageChange={setPage}
                  />
                )}
              </>
            )
      }

      {/* ── Diff drawer ─────────────────────────────────────────────────────── */}
      {drawerRevision && (
        <DetailDrawer
          title={`Revision #${drawerRevision.revisionNumber} — ${drawerRevision.entityType ?? ''}`}
          onClose={() => { setDrawerRevision(null); setDiffData(null) }}
        >
          {diffLoading && <LoadingState />}
          {!diffLoading && diffData?.error && (
            <ErrorState message={diffData.error} onRetry={() => {}} />
          )}
          {!diffLoading && diffData && !diffData.error && (
            <DiffView detail={diffData} />
          )}
        </DetailDrawer>
      )}
    </main>
  )
}

// ── Diff view component ───────────────────────────────────────────────────────

/**
 * Renders field-level before/after diff for a single revision.
 *
 * @param {{ detail: object }} props
 */
function DiffView({ detail }) {
  const fields = detail.fields ?? []
  if (fields.length === 0) {
    return <EmptyState message="No field changes recorded for this revision." />
  }

  return (
    <table
      style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--fs-sm)' }}
      aria-label="Field diff"
    >
      <thead>
        <tr style={{ borderBottom: '1px solid var(--color-border-hairline)' }}>
          <th style={{ textAlign: 'left', padding: 'var(--space-2)' }}>Field</th>
          <th style={{ textAlign: 'left', padding: 'var(--space-2)' }}>Before</th>
          <th style={{ textAlign: 'left', padding: 'var(--space-2)' }}>After</th>
        </tr>
      </thead>
      <tbody>
        {fields.filter(f => f.changed).map(f => (
          <tr
            key={f.name}
            style={{ borderBottom: '1px solid var(--color-border-hairline)', background: 'var(--color-surface-changed, var(--color-surface-subtle))' }}
          >
            <td style={{ padding: 'var(--space-2)', fontWeight: 'var(--fw-medium)' }}>
              {f.name}
              {f.masked && <abbr title="Value masked — PII field" style={{ marginLeft: 'var(--space-1)', fontSize: '0.8em' }}>🔒</abbr>}
            </td>
            <td style={{ padding: 'var(--space-2)', color: 'var(--color-text-danger)' }}>
              <code>{f.before != null ? String(f.before) : '—'}</code>
            </td>
            <td style={{ padding: 'var(--space-2)', color: 'var(--color-text-success)' }}>
              <code>{f.after != null ? String(f.after) : '—'}</code>
            </td>
          </tr>
        ))}
        {fields.filter(f => !f.changed).map(f => (
          <tr key={f.name} style={{ borderBottom: '1px solid var(--color-border-hairline)', opacity: 0.6 }}>
            <td style={{ padding: 'var(--space-2)' }}>{f.name}</td>
            <td style={{ padding: 'var(--space-2)' }} colSpan={2}>
              <code style={{ color: 'var(--color-text-subtle)' }}>{f.after != null ? String(f.after) : '—'}</code>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
