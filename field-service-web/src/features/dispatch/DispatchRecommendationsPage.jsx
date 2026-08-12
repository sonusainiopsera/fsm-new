/**
 * @fileoverview DispatchRecommendationsPage — ranked technician shortlist for a work order.
 *
 * Component tree:
 *   DispatchRecommendationsPage
 *     ├─ WorkOrderContextHeader
 *     ├─ DegradedDataBanner (aria-live, when travel or parts data is degraded)
 *     ├─ PartsWarningBanner (when partsWarning present in meta)
 *     ├─ RecommendationList → RecommendationCard[]
 *     │     └─ FactorBreakdownPanel (expandable)
 *     └─ LoadMoreCursorButton
 *
 * State handling:
 * - Loading → skeleton rows
 * - Zero candidates → exclusion summary panel
 * - Error (403) → permission-denied state
 * - Error (422) → not-assignable explanation
 * - Error (5xx) → retryable degraded-service panel
 * - Success with data → ranked list
 *
 * @module features/dispatch/DispatchRecommendationsPage
 */
import {
  LoadingState,
  EmptyState,
  ErrorState,
  PermissionDeniedState,
  DegradedState,
} from '../../components/index.js'
import { RecommendationCard } from './components/RecommendationCard.jsx'
import { useRecommendations, flattenCandidates, extractMeta } from './api/useRecommendations.js'

/**
 * @param {{
 *   workOrderId: string,
 *   workOrderContext?: {
 *     reference?: string,
 *     customerName?: string,
 *     siteName?: string,
 *     assetLabel?: string,
 *     priority?: string,
 *     responseDueAt?: string | null,
 *     resolutionDueAt?: string | null,
 *     state?: string
 *   } | null,
 *   pageSize?: number,
 *   onAssign?: (candidate: import('./api/useRecommendations.js').CandidateDto) => void
 * }} props
 */
export default function DispatchRecommendationsPage({
  workOrderId,
  workOrderContext = null,
  pageSize = 20,
  onAssign,
}) {
  const {
    data,
    isLoading,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
    error,
    refetch,
  } = useRecommendations({ workOrderId, size: pageSize })

  // ── Error states ─────────────────────────────────────────────────────────────

  if (error) {
    const status = error?.status ?? 0
    if (status === 403) {
      return (
        <div style={pageWrap}>
          <WorkOrderContextHeader ctx={workOrderContext} />
          <PermissionDeniedState />
        </div>
      )
    }
    if (status === 422) {
      return (
        <div style={pageWrap}>
          <WorkOrderContextHeader ctx={workOrderContext} />
          <EmptyState
            message={
              error.message ?? 'This work order is not in an assignable state.'
            }
          />
        </div>
      )
    }
    return (
      <div style={pageWrap}>
        <WorkOrderContextHeader ctx={workOrderContext} />
        <ErrorState onRetry={refetch} message={error.message} />
      </div>
    )
  }

  // ── Loading state ─────────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div style={pageWrap}>
        <WorkOrderContextHeader ctx={workOrderContext} />
        <LoadingState />
      </div>
    )
  }

  // ── Success: extract candidates and meta ──────────────────────────────────────

  const candidates = flattenCandidates(data)
  const meta = extractMeta(data)
  const isDataDegraded = meta?.travelEstimateDegraded || meta?.partsDataDegraded

  // ── Zero-candidates state ─────────────────────────────────────────────────────

  if (!candidates.length) {
    return (
      <div style={pageWrap}>
        <WorkOrderContextHeader ctx={workOrderContext} />
        <ExclusionSummaryPanel exclusionSummary={meta?.exclusionSummary ?? []} />
      </div>
    )
  }

  // ── Main view ────────────────────────────────────────────────────────────────

  return (
    <div style={pageWrap}>
      <WorkOrderContextHeader ctx={workOrderContext} />

      {/* Aggregate degraded data banner */}
      {isDataDegraded && (
        <div
          role="status"
          aria-live="polite"
          aria-label="Some recommendation data is estimated"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--token-space-2)',
            padding: 'var(--token-space-3) var(--token-space-4)',
            marginBottom: 'var(--token-space-4)',
            borderRadius: 'var(--token-radius-card)',
            background: 'var(--token-warning-subtle)',
            border: '1px solid var(--token-warning-default)',
            fontSize: 'var(--token-fs-13)',
            fontFamily: 'var(--token-family-base)',
            color: 'var(--token-warning-emphasis)',
          }}
        >
          <span aria-hidden="true">⚠</span>
          <span>
            {meta?.travelEstimateDegraded && meta?.partsDataDegraded
              ? 'Travel and parts data are estimated — live feeds temporarily unavailable.'
              : meta?.travelEstimateDegraded
              ? 'Travel times are estimated — live traffic data temporarily unavailable.'
              : 'Parts availability data is estimated — live inventory temporarily unavailable.'}
          </span>
        </div>
      )}

      {/* Parts-unavailable warning from meta */}
      {meta?.partsWarning && (
        <div
          role="alert"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--token-space-2)',
            padding: 'var(--token-space-3) var(--token-space-4)',
            marginBottom: 'var(--token-space-4)',
            borderRadius: 'var(--token-radius-card)',
            background: 'var(--token-danger-subtle)',
            border: '1px solid var(--token-danger-default)',
            fontSize: 'var(--token-fs-13)',
            fontFamily: 'var(--token-family-base)',
            color: 'var(--token-danger-emphasis)',
          }}
        >
          <span aria-hidden="true">⊘</span>
          <span>
            Parts required for this work order are unavailable.
            {meta.partsWarning.shortfalls?.length > 0 &&
              ` ${meta.partsWarning.shortfalls.length} part${meta.partsWarning.shortfalls.length !== 1 ? 's' : ''} short.`}
          </span>
        </div>
      )}

      {/* Candidate list */}
      <ul
        role="list"
        aria-label={`${candidates.length} technician recommendation${candidates.length !== 1 ? 's' : ''}`}
        style={{
          margin: 0,
          padding: 0,
          listStyle: 'none',
          display: 'flex',
          flexDirection: 'column',
          gap: 'var(--token-space-3)',
        }}
      >
        {candidates.map((candidate) => (
          <li key={candidate.technicianId}>
            <RecommendationCard candidate={candidate} onAssign={onAssign} />
          </li>
        ))}
      </ul>

      {/* Load more */}
      {hasNextPage && (
        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            marginTop: 'var(--token-space-5)',
          }}
        >
          <button
            type="button"
            onClick={() => fetchNextPage()}
            disabled={isFetchingNextPage}
            aria-busy={isFetchingNextPage}
            style={{
              padding: 'var(--token-space-2) var(--token-space-6)',
              fontSize: 'var(--token-fs-14)',
              fontFamily: 'var(--token-family-base)',
              borderRadius: 'var(--token-radius-control)',
              border: '1px solid var(--token-border-default)',
              background: 'var(--token-surface-card)',
              color: 'var(--token-text-primary)',
              cursor: isFetchingNextPage ? 'default' : 'pointer',
            }}
          >
            {isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </div>
  )
}

// ── Sub-components ────────────────────────────────────────────────────────────

/**
 * Compact work order context header.
 *
 * @param {{ ctx: Parameters<typeof DispatchRecommendationsPage>[0]['workOrderContext'] }} props
 */
function WorkOrderContextHeader({ ctx }) {
  if (!ctx) return null
  return (
    <header
      aria-label="Work order context"
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 'var(--token-space-3) var(--token-space-6)',
        padding: 'var(--token-space-4)',
        marginBottom: 'var(--token-space-5)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-surface-raised)',
        border: '1px solid var(--token-border-default)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      {ctx.reference && (
        <CtxField label="Reference" value={ctx.reference} />
      )}
      {ctx.customerName && (
        <CtxField label="Customer" value={ctx.customerName} />
      )}
      {ctx.siteName && (
        <CtxField label="Site" value={ctx.siteName} />
      )}
      {ctx.assetLabel && (
        <CtxField label="Asset" value={ctx.assetLabel} />
      )}
      {ctx.priority && (
        <CtxField label="Priority" value={ctx.priority} />
      )}
      {ctx.responseDueAt && (
        <CtxField label="Response due" value={formatDate(ctx.responseDueAt)} />
      )}
      {ctx.resolutionDueAt && (
        <CtxField label="Resolution due" value={formatDate(ctx.resolutionDueAt)} />
      )}
    </header>
  )
}

/**
 * @param {{ label: string, value: string }} props
 */
function CtxField({ label, value }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}>
      <span
        style={{
          fontSize: 'var(--token-fs-11)',
          fontWeight: 600,
          color: 'var(--token-text-secondary)',
          textTransform: 'uppercase',
          letterSpacing: '0.06em',
        }}
      >
        {label}
      </span>
      <span
        style={{
          fontSize: 'var(--token-fs-14)',
          color: 'var(--token-text-primary)',
          fontWeight: 500,
        }}
      >
        {value}
      </span>
    </div>
  )
}

/**
 * Zero-candidates panel with exclusion summary.
 *
 * @param {{ exclusionSummary: import('./api/useRecommendations.js').ExclusionSummaryEntry[] }} props
 */
function ExclusionSummaryPanel({ exclusionSummary }) {
  return (
    <div
      data-testid="zero-candidates-panel"
      style={{
        padding: 'var(--token-space-8)',
        textAlign: 'center',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      <p
        style={{
          fontSize: 'var(--token-fs-16)',
          fontWeight: 600,
          color: 'var(--token-text-primary)',
          margin: '0 0 var(--token-space-2)',
        }}
      >
        No eligible technicians found
      </p>
      <p
        style={{
          fontSize: 'var(--token-fs-14)',
          color: 'var(--token-text-secondary)',
          margin: '0 0 var(--token-space-5)',
        }}
      >
        All candidates were excluded. Review the reasons below and adjust the work order
        requirements or contact an administrator.
      </p>
      {exclusionSummary.length > 0 && (
        <ul
          role="list"
          aria-label="Exclusion reasons"
          style={{
            display: 'inline-flex',
            flexDirection: 'column',
            gap: 'var(--token-space-2)',
            listStyle: 'none',
            padding: 0,
            margin: 0,
            textAlign: 'left',
          }}
        >
          {exclusionSummary.map((entry) => (
            <li
              key={entry.reason}
              style={{
                display: 'flex',
                gap: 'var(--token-space-3)',
                alignItems: 'baseline',
                fontSize: 'var(--token-fs-13)',
                color: 'var(--token-text-secondary)',
              }}
            >
              <span
                style={{
                  fontVariantNumeric: 'var(--token-numeric)',
                  fontWeight: 700,
                  color: 'var(--token-text-primary)',
                  minWidth: '2rem',
                  textAlign: 'right',
                }}
              >
                {entry.count}
              </span>
              <span>{formatExclusionReason(entry.reason)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** @param {string} iso */
function formatDate(iso) {
  try {
    return new Intl.DateTimeFormat(undefined, {
      month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    }).format(new Date(iso))
  } catch {
    return iso
  }
}

/** @param {string} reason */
function formatExclusionReason(reason) {
  const MAP = {
    CERTIFICATION_MISSING:  'Missing required certification',
    CERTIFICATION_EXPIRED:  'Certification expired',
    INACTIVE_TECHNICIAN:    'Technician is inactive',
    UNAVAILABLE_IN_WINDOW:  'Unavailable during the required window',
    OUT_OF_REACH:           'Outside service area',
  }
  return MAP[reason] ?? reason
}

const pageWrap = {
  padding: 'var(--token-space-5)',
  maxWidth: '860px',
  margin: '0 auto',
  fontFamily: 'var(--token-family-base)',
}
