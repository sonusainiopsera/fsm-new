/**
 * @fileoverview RecommendationCard — a single ranked candidate row.
 *
 * Displays: rank badge, technician name, composite score bar, travel-estimate
 * degraded indicator (per-row), parts availability summary, and an expandable
 * FactorBreakdownPanel.
 *
 * The client NEVER re-ranks, re-scores or filters data from the server payload.
 */
import { FactorBreakdownPanel } from './FactorBreakdownPanel.jsx'

/**
 * @param {{
 *   candidate: import('../api/useRecommendations.js').CandidateDto,
 *   onAssign?: (candidate: import('../api/useRecommendations.js').CandidateDto) => void
 * }} props
 */
export function RecommendationCard({ candidate, onAssign }) {
  const scorePct = Math.round(Math.min(1, Math.max(0, candidate.score)) * 100)
  const label = candidate.technicianName ?? candidate.technicianId

  return (
    <article
      aria-label={`Rank ${candidate.rank}: ${label}`}
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-3)',
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        border: '1px solid var(--token-border-default)',
        background: 'var(--token-surface-card)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      {/* Header: rank + name + score */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 'var(--token-space-3)',
        }}
      >
        {/* Rank badge */}
        <span
          aria-label={`Rank ${candidate.rank}`}
          style={{
            flexShrink: 0,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: '2rem',
            height: '2rem',
            borderRadius: 'var(--token-radius-pill)',
            background: 'var(--token-neutral-200)',
            color: 'var(--token-text-primary)',
            fontWeight: 700,
            fontSize: 'var(--token-fs-14)',
            fontVariantNumeric: 'var(--token-numeric)',
          }}
        >
          {candidate.rank}
        </span>

        {/* Technician name */}
        <span
          style={{
            flex: 1,
            fontSize: 'var(--token-fs-15)',
            fontWeight: 600,
            color: 'var(--token-text-primary)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
          title={label}
        >
          {label}
        </span>

        {/* Travel-estimate degraded indicator (per-row) */}
        {candidate.travelEstimateDegraded && (
          <span
            aria-label="Travel estimate only — live travel data unavailable"
            title="Travel estimate only — live travel data unavailable"
            style={{
              fontSize: 'var(--token-fs-12)',
              color: 'var(--token-warning-emphasis)',
              border: '1px solid var(--token-warning-default)',
              borderRadius: 'var(--token-radius-control)',
              padding: '2px var(--token-space-2)',
              whiteSpace: 'nowrap',
            }}
          >
            ~travel est.
          </span>
        )}

        {/* Composite score */}
        <div
          style={{
            flexShrink: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'flex-end',
            gap: 'var(--token-space-1)',
          }}
        >
          <span
            aria-label={`Score: ${scorePct} out of 100`}
            style={{
              fontSize: 'var(--token-fs-16)',
              fontWeight: 700,
              fontVariantNumeric: 'var(--token-numeric)',
              color: 'var(--token-text-primary)',
            }}
          >
            {scorePct}
          </span>
          <span
            style={{
              fontSize: 'var(--token-fs-11)',
              color: 'var(--token-text-secondary)',
              letterSpacing: '0.02em',
            }}
          >
            / 100
          </span>
        </div>
      </div>

      {/* Score track */}
      <div
        role="progressbar"
        aria-valuenow={scorePct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`${label} composite score: ${scorePct}%`}
        style={{
          height: '4px',
          borderRadius: 'var(--token-radius-pill)',
          background: 'var(--token-neutral-200)',
          overflow: 'hidden',
        }}
      >
        <div
          aria-hidden="true"
          style={{
            height: '100%',
            width: `${scorePct}%`,
            borderRadius: 'var(--token-radius-pill)',
            background: 'var(--token-neutral-600)',
          }}
        />
      </div>

      {/* Parts availability */}
      {candidate.partsAvailability && candidate.partsAvailability.status !== 'AVAILABLE' && (
        <PartsAvailabilitySummary parts={candidate.partsAvailability} />
      )}

      {/* Factor breakdown panel */}
      <FactorBreakdownPanel factors={candidate.factors ?? []} candidateLabel={label} />

      {/* Assign button */}
      {onAssign && (
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <button
            type="button"
            onClick={() => onAssign(candidate)}
            style={{
              padding: 'var(--token-space-2) var(--token-space-5)',
              fontSize: 'var(--token-fs-14)',
              fontFamily: 'var(--token-family-base)',
              fontWeight: 600,
              borderRadius: 'var(--token-radius-control)',
              border: '1px solid var(--token-accent-500)',
              background: 'var(--token-accent-500)',
              color: 'var(--token-on-accent)',
              cursor: 'pointer',
            }}
          >
            Assign
          </button>
        </div>
      )}
    </article>
  )
}

/**
 * @param {{ parts: import('../api/useRecommendations.js').PartsAvailabilitySummary }} props
 */
function PartsAvailabilitySummary({ parts }) {
  const label =
    parts.status === 'PARTIAL'
      ? `Parts partially available (${Math.round(parts.satisfactionRatio * 100)}%)`
      : 'Parts unavailable'

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 'var(--token-space-2)',
        padding: 'var(--token-space-2) var(--token-space-3)',
        borderRadius: 'var(--token-radius-control)',
        background: 'var(--token-warning-subtle)',
        border: '1px solid var(--token-warning-default)',
        fontSize: 'var(--token-fs-12)',
        color: 'var(--token-warning-emphasis)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      <span aria-hidden="true">⚠</span>
      <span>{label}</span>
      {parts.shortfalls?.length > 0 && (
        <span style={{ marginLeft: 'auto', color: 'var(--token-text-secondary)' }}>
          {parts.shortfalls.length} part{parts.shortfalls.length !== 1 ? 's' : ''} short
        </span>
      )}
    </div>
  )
}
