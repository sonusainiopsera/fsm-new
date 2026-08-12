/**
 * @fileoverview FactorBreakdownPanel — expandable per-technician factor breakdown.
 *
 * Renders all factors supplied by the server. The client NEVER recomputes, re-ranks
 * or re-derives any values — all numbers and explanation text come verbatim from the
 * server response.
 *
 * Accessibility:
 * - Toggle button carries aria-expanded and a visible focus ring from tokens.
 * - Factor bar percentages have a text equivalent via aria-label.
 * - The panel is a list so screen readers announce item count.
 */
import { useState } from 'react'

/**
 * @param {{
 *   factors: import('../api/useRecommendations.js').FactorDto[],
 *   candidateLabel: string
 * }} props
 */
export function FactorBreakdownPanel({ factors = [], candidateLabel }) {
  const [open, setOpen] = useState(false)

  if (!factors.length) return null

  return (
    <div style={{ marginTop: 'var(--token-space-3)' }}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={`factors-${candidateLabel}`}
        onClick={() => setOpen((v) => !v)}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 'var(--token-space-1)',
          padding: 'var(--token-space-1) 0',
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          fontSize: 'var(--token-fs-13)',
          fontFamily: 'var(--token-family-base)',
          color: 'var(--token-text-secondary)',
          textDecoration: 'underline',
          textUnderlineOffset: '2px',
        }}
      >
        <span aria-hidden="true">{open ? '▲' : '▼'}</span>
        {open ? 'Hide factors' : `Show ${factors.length} factor${factors.length !== 1 ? 's' : ''}`}
      </button>

      {open && (
        <ul
          id={`factors-${candidateLabel}`}
          role="list"
          aria-label={`Factor breakdown for ${candidateLabel}`}
          style={{
            margin: 'var(--token-space-2) 0 0',
            padding: 0,
            listStyle: 'none',
            display: 'flex',
            flexDirection: 'column',
            gap: 'var(--token-space-4)',
          }}
        >
          {factors.map((factor) => (
            <li key={factor.factorCode}>
              <FactorRow factor={factor} />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/**
 * Single factor row: label + weight + normalised bar + explanation text.
 *
 * @param {{ factor: import('../api/useRecommendations.js').FactorDto }} props
 */
function FactorRow({ factor }) {
  const pct = Math.round(Math.min(1, Math.max(0, factor.normalisedValue)) * 100)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-2)' }}>
      {/* Label + weight row */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 'var(--token-space-2)',
        }}
      >
        <span
          style={{
            fontSize: 'var(--token-fs-13)',
            fontFamily: 'var(--token-family-base)',
            color: 'var(--token-text-primary)',
            fontWeight: 500,
          }}
        >
          {factor.factorCode}
          {factor.degraded && (
            <span
              aria-label="estimated only"
              title="Estimated only — live data unavailable"
              style={{
                marginLeft: 'var(--token-space-1)',
                fontSize: 'var(--token-fs-11)',
                color: 'var(--token-warning-emphasis)',
              }}
            >
              ~est
            </span>
          )}
        </span>
        <span
          style={{
            fontSize: 'var(--token-fs-12)',
            fontFamily: 'var(--token-family-base)',
            color: 'var(--token-text-secondary)',
            whiteSpace: 'nowrap',
          }}
        >
          weight {Math.round(factor.weight * 100)}%
        </span>
      </div>

      {/* Normalised contribution bar with text equivalent */}
      <div
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`${factor.factorCode} normalised contribution: ${pct}%`}
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
            width: `${pct}%`,
            borderRadius: 'var(--token-radius-pill)',
            background: 'var(--token-neutral-500)',
          }}
        />
      </div>

      {/* Explanation text — rendered verbatim from server, never recomputed */}
      {factor.explanation && (
        <p
          style={{
            margin: 0,
            fontSize: 'var(--token-fs-12)',
            fontFamily: 'var(--token-family-base)',
            color: 'var(--token-text-secondary)',
            lineHeight: 1.5,
          }}
        >
          {factor.explanation}
        </p>
      )}
    </div>
  )
}
