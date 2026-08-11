/**
 * @fileoverview ScorePresentation — monochrome numeral, 4 px neutral track, per-factor micro-bars.
 * No semantic color, no medals, no celebratory treatment (BR-33).
 */

/**
 * @typedef {{ label: string, weight: number, normalizedValue: number }} ScoreFactor
 */

/**
 * @param {{
 *   score: number,
 *   maxScore?: number,
 *   label?: string,
 *   factors?: ScoreFactor[]
 * }} props
 */
export function ScorePresentation({ score, maxScore = 100, label = 'Score', factors = [] }) {
  const pct = Math.min(100, Math.round((score / maxScore) * 100))

  return (
    <div
      data-component="score-presentation"
      style={{ fontFamily: 'var(--token-family-base)', display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}
    >
      {/* Composite score — monochrome numeral */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-2)' }}>
        <span style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', fontWeight: 500 }}>
          {label}
        </span>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--token-space-2)' }}>
          <span
            aria-label={`${label}: ${score} out of ${maxScore}`}
            style={{
              fontSize: 'var(--token-fs-30)',
              fontVariantNumeric: 'var(--token-numeric)',
              fontWeight: 700,
              color: 'var(--token-text-primary)',
              letterSpacing: 'var(--token-ls-tight)',
            }}
          >
            {score}
          </span>
          <span style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)' }}>
            / {maxScore}
          </span>
        </div>

        {/* 4 px neutral progress track */}
        <div
          role="progressbar"
          aria-valuenow={score}
          aria-valuemin={0}
          aria-valuemax={maxScore}
          aria-label={`${label} progress: ${pct}%`}
          style={{
            height: '4px',
            borderRadius: 'var(--token-radius-pill)',
            background: 'var(--token-neutral-200)',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              height: '100%',
              width: `${pct}%`,
              borderRadius: 'var(--token-radius-pill)',
              background: 'var(--token-neutral-600)',
            }}
          />
        </div>
      </div>

      {/* Per-factor micro-bars with visible text labels (no semantic color) */}
      {factors.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-3)' }}>
          {factors.map((factor, i) => (
            <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}>
                  {factor.label}
                </span>
                <span
                  style={{
                    fontSize: 'var(--token-fs-12)',
                    fontVariantNumeric: 'var(--token-numeric)',
                    color: 'var(--token-text-secondary)',
                  }}
                  aria-label={`${factor.label}: ${Math.round(factor.normalizedValue * 100)}%`}
                >
                  {Math.round(factor.normalizedValue * 100)}%
                </span>
              </div>
              <div
                style={{
                  height: '4px',
                  borderRadius: 'var(--token-radius-pill)',
                  background: 'var(--token-neutral-200)',
                  overflow: 'hidden',
                }}
                aria-hidden="true"
              >
                <div
                  style={{
                    height: '100%',
                    width: `${Math.round(factor.normalizedValue * 100)}%`,
                    borderRadius: 'var(--token-radius-pill)',
                    background: 'var(--token-neutral-500)',
                  }}
                />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
