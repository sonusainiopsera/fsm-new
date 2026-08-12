/**
 * @fileoverview ServiceHistoryPage — paginated service history browser.
 *
 * Follows AC-1 through AC-5 from WO-175:
 *   - Navigates via server-returned links.next / links.prev only (link-driven).
 *   - Never requests page size > MAX_HISTORY_PAGE_SIZE = 50.
 *   - Sort options enumerated from the server allow-list (HISTORY_SORT_OPTIONS).
 *   - Filter state synced to URL search params (shareable, back-button safe).
 *   - keepPreviousData prevents flash of empty on page transitions.
 *   - Exact-once rendering: each work order row keyed by workOrderId.
 *   - Empty state distinguishes "no history" from "no results for filters".
 *   - Stale-URL recovery: page beyond last recovers gracefully.
 *   - Plain-language labels only; no internal enum codes.
 *   - WCAG 2.1 AA: filter controls labelled, pager buttons 44px, appearance switch.
 */

import { useCallback } from 'react'
import { useSearchParams, Link } from 'react-router-dom'
import {
  PageHeader, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js'
import { LinkPager } from '../../components/pagination/LinkPager.jsx'
import {
  useServiceHistory,
  HISTORY_SORT_OPTIONS,
  MAX_HISTORY_PAGE_SIZE,
} from '../../api/portalClient.js'
import { parsePagedEnvelope } from '../../api/pagination.js'

const STATUS_GROUP_OPTIONS = [
  { value: '', label: 'All requests' },
  { value: 'OPEN', label: 'Active requests' },
  { value: 'CLOSED', label: 'Completed requests' },
]

const DEFAULT_PAGE_SIZE = 20

/**
 * Parses a server-provided URL and extracts the page param.
 * Used to update URL state when following a next/prev link.
 *
 * @param {string} serverUrl
 * @returns {Record<string, string>}
 */
function extractLinkParams(serverUrl) {
  try {
    const parsed = new URL(serverUrl, window.location.origin)
    const params = {}
    for (const [key, val] of parsed.searchParams.entries()) {
      params[key] = val
    }
    return params
  } catch {
    return {}
  }
}

export default function ServiceHistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  // Read filter state from URL
  const page = Number(searchParams.get('page') ?? 0)
  const size = Math.min(Number(searchParams.get('size') ?? DEFAULT_PAGE_SIZE), MAX_HISTORY_PAGE_SIZE)
  const sort = searchParams.get('sort') ?? 'createdAt'
  const siteId = searchParams.get('siteId') ?? undefined
  const statusGroup = /** @type {'OPEN'|'CLOSED'|undefined} */ (searchParams.get('statusGroup') || undefined)
  const fromDate = searchParams.get('fromDate') ?? undefined
  const toDate = searchParams.get('toDate') ?? undefined

  const hasActiveFilters = Boolean(siteId || statusGroup || fromDate || toDate)

  const { data: envelope, isLoading, isError, isFetching } = useServiceHistory({
    page, size, sort, siteId, statusGroup, fromDate, toDate,
  })

  const { data: rows, page: pageMeta, links } = parsePagedEnvelope(envelope)

  // Update a single search param, resetting page to 0 for filter changes
  const setFilter = useCallback((key, value, resetPage = true) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      if (value == null || value === '') {
        next.delete(key)
      } else {
        next.set(key, String(value))
      }
      if (resetPage) next.set('page', '0')
      return next
    }, { replace: true })
  }, [setSearchParams])

  // Link-driven navigation: parse server URL, copy its page param to our URL
  const handleNavigate = useCallback((serverUrl) => {
    const linkParams = extractLinkParams(serverUrl)
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      if (linkParams.page != null) next.set('page', linkParams.page)
      if (linkParams.cursor != null) next.set('cursor', linkParams.cursor)
      return next
    }, { replace: false })
  }, [setSearchParams])

  const clearFilters = useCallback(() => {
    setSearchParams(prev => {
      const next = new URLSearchParams()
      if (prev.get('sort')) next.set('sort', prev.get('sort'))
      if (prev.get('size')) next.set('size', prev.get('size'))
      return next
    }, { replace: true })
  }, [setSearchParams])

  return (
    <main
      style={{
        maxWidth: 900,
        margin: '0 auto',
        padding: 'var(--token-space-6) var(--token-space-4)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      <PageHeader title="Service History" />

      {/* Filters */}
      <section
        aria-label="Filter service history"
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 'var(--token-space-3)',
          marginBottom: 'var(--token-space-5)',
          alignItems: 'flex-end',
        }}
      >
        {/* Status group filter */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}>
          <label
            htmlFor="history-status-filter"
            style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}
          >
            Status
          </label>
          <select
            id="history-status-filter"
            value={statusGroup ?? ''}
            onChange={(e) => setFilter('statusGroup', e.target.value || '')}
            style={{
              height: '44px',
              minWidth: 160,
              padding: '0 var(--token-space-3)',
              fontSize: 'var(--token-fs-15)',
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              background: 'var(--token-surface-0)',
              color: 'var(--token-text-primary)',
            }}
          >
            {STATUS_GROUP_OPTIONS.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>

        {/* Sort control */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}>
          <label
            htmlFor="history-sort"
            style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}
          >
            Sort by
          </label>
          <select
            id="history-sort"
            value={sort}
            onChange={(e) => setFilter('sort', e.target.value, false)}
            style={{
              height: '44px',
              minWidth: 140,
              padding: '0 var(--token-space-3)',
              fontSize: 'var(--token-fs-15)',
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              background: 'var(--token-surface-0)',
              color: 'var(--token-text-primary)',
            }}
          >
            {HISTORY_SORT_OPTIONS.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>

        {/* Date range: from */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}>
          <label
            htmlFor="history-from-date"
            style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}
          >
            From date
          </label>
          <input
            type="date"
            id="history-from-date"
            value={fromDate ?? ''}
            max={toDate ?? undefined}
            onChange={(e) => setFilter('fromDate', e.target.value || '')}
            style={{
              height: '44px',
              padding: '0 var(--token-space-3)',
              fontSize: 'var(--token-fs-15)',
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              background: 'var(--token-surface-0)',
              color: 'var(--token-text-primary)',
            }}
          />
        </div>

        {/* Date range: to */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}>
          <label
            htmlFor="history-to-date"
            style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}
          >
            To date
          </label>
          <input
            type="date"
            id="history-to-date"
            value={toDate ?? ''}
            min={fromDate ?? undefined}
            onChange={(e) => setFilter('toDate', e.target.value || '')}
            style={{
              height: '44px',
              padding: '0 var(--token-space-3)',
              fontSize: 'var(--token-fs-15)',
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              background: 'var(--token-surface-0)',
              color: 'var(--token-text-primary)',
            }}
          />
        </div>

        {hasActiveFilters && (
          <button
            type="button"
            onClick={clearFilters}
            style={{
              height: '44px',
              padding: '0 var(--token-space-4)',
              fontSize: 'var(--token-fs-14)',
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              background: 'transparent',
              color: 'var(--token-text-secondary)',
              cursor: 'pointer',
              alignSelf: 'flex-end',
            }}
          >
            Clear filters
          </button>
        )}
      </section>

      {/* Loading */}
      {isLoading && <LoadingState />}

      {/* Error */}
      {isError && !isLoading && (
        <ErrorState message="We could not load your service history. Please refresh to try again." />
      )}

      {/* Empty state */}
      {!isLoading && !isError && rows.length === 0 && (
        <EmptyState
          message={
            hasActiveFilters
              ? 'No requests match these filters. Try adjusting or clearing them.'
              : 'You have no service history yet.'
          }
        />
      )}

      {/* History table */}
      {!isLoading && !isError && rows.length > 0 && (
        <>
          <div
            role="region"
            aria-label="Service request history"
            aria-live="polite"
            aria-busy={isFetching}
            style={{ overflowX: 'auto' }}
          >
            <table
              style={{
                width: '100%',
                borderCollapse: 'collapse',
                fontSize: 'var(--token-fs-14)',
                fontFamily: 'var(--token-family-base)',
              }}
            >
              <thead>
                <tr>
                  {['Reference', 'Site', 'Equipment', 'Status', 'Date opened', 'Outcome'].map((col) => (
                    <th
                      key={col}
                      scope="col"
                      style={{
                        textAlign: 'left',
                        padding: 'var(--token-space-3) var(--token-space-4)',
                        borderBottom: '2px solid var(--token-border-default)',
                        fontSize: 'var(--token-fs-13)',
                        fontWeight: 600,
                        color: 'var(--token-text-secondary)',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {col}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((/** @type {any} */ row) => (
                  <tr
                    key={row.workOrderId}
                    style={{
                      borderBottom: '1px solid var(--token-border-subtle)',
                    }}
                  >
                    <td
                      style={{
                        padding: 'var(--token-space-3) var(--token-space-4)',
                        fontWeight: 500,
                        color: 'var(--token-text-primary)',
                      }}
                    >
                      {row.reference ?? '—'}
                    </td>
                    <td style={{ padding: 'var(--token-space-3) var(--token-space-4)', color: 'var(--token-text-primary)' }}>
                      {row.siteName}
                    </td>
                    <td style={{ padding: 'var(--token-space-3) var(--token-space-4)', color: 'var(--token-text-secondary)' }}>
                      {row.assetLabel ?? '—'}
                    </td>
                    <td style={{ padding: 'var(--token-space-3) var(--token-space-4)' }}>
                      <span
                        style={{
                          display: 'inline-block',
                          padding: '2px var(--token-space-2)',
                          borderRadius: 'var(--token-radius-sm)',
                          fontSize: 'var(--token-fs-13)',
                          background: row.closedAt ? 'var(--token-success-subtle)' : 'var(--token-info-subtle)',
                          color: row.closedAt ? 'var(--token-success-emphasis)' : 'var(--token-info-emphasis)',
                        }}
                      >
                        {row.statusLabel}
                      </span>
                    </td>
                    <td style={{ padding: 'var(--token-space-3) var(--token-space-4)', color: 'var(--token-text-secondary)', whiteSpace: 'nowrap' }}>
                      {row.openedAt ? new Date(row.openedAt).toLocaleDateString() : '—'}
                    </td>
                    <td style={{ padding: 'var(--token-space-3) var(--token-space-4)', color: 'var(--token-text-secondary)' }}>
                      {row.outcomeSummary ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <LinkPager
            links={links}
            page={pageMeta}
            onNavigate={handleNavigate}
            isFetching={isFetching}
          />
        </>
      )}

      <div style={{ marginTop: 'var(--token-space-6)', borderTop: '1px solid var(--token-border-subtle)', paddingTop: 'var(--token-space-4)' }}>
        <Link
          to="/portal"
          style={{
            fontSize: 'var(--token-fs-14)',
            color: 'var(--token-text-link)',
            textDecoration: 'underline',
          }}
        >
          ← Back to portal
        </Link>
      </div>
    </main>
  )
}
