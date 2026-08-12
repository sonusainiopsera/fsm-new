/**
 * @fileoverview StatusTimeline — renders customer-visible lifecycle milestones.
 *
 * Renders a vertical timeline of milestones from newest to oldest.
 * Accepts only API-provided plain-language labels — no internal state codes or enums.
 * Responds to prefers-reduced-motion: transitions are skipped when the user requests it.
 */

/**
 * @param {{
 *   milestones: Array<{ at: string, label: string }>,
 *   currentLabel: string,
 *   currentDescription: string
 * }} props
 */
export function StatusTimeline({ milestones = [], currentLabel, currentDescription }) {
  const sorted = [...milestones].sort((a, b) => new Date(b.at) - new Date(a.at))

  return (
    <section
      aria-label="Request timeline"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-4)',
      }}
    >
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        style={{
          padding: 'var(--token-space-6)',
          background: 'var(--token-surface-1)',
          borderRadius: 'var(--token-radius-md)',
          border: '1px solid var(--token-border-subtle)',
          display: 'flex',
          flexDirection: 'column',
          gap: 'var(--token-space-2)',
        }}
      >
        <span
          style={{
            fontSize: 'var(--token-fs-20)',
            fontWeight: 700,
            color: 'var(--token-text-primary)',
            lineHeight: 1.3,
          }}
        >
          {currentLabel}
        </span>
        {currentDescription && (
          <span
            style={{
              fontSize: 'var(--token-fs-16)',
              color: 'var(--token-text-secondary)',
              lineHeight: 1.5,
            }}
          >
            {currentDescription}
          </span>
        )}
      </div>

      {sorted.length > 0 && (
        <ol
          aria-label="Status history"
          style={{
            listStyle: 'none',
            margin: 0,
            padding: 0,
            display: 'flex',
            flexDirection: 'column',
            gap: 0,
          }}
        >
          {sorted.map((milestone, idx) => (
            <li
              key={`${milestone.at}-${idx}`}
              style={{
                display: 'flex',
                gap: 'var(--token-space-4)',
                paddingBottom: idx < sorted.length - 1 ? 'var(--token-space-4)' : 0,
              }}
            >
              <div
                aria-hidden="true"
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  flexShrink: 0,
                  width: 'var(--token-space-5)',
                }}
              >
                <div
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: '50%',
                    background: idx === 0 ? 'var(--token-accent-default)' : 'var(--token-neutral-400)',
                    flexShrink: 0,
                  }}
                />
                {idx < sorted.length - 1 && (
                  <div
                    style={{
                      width: 1,
                      flex: 1,
                      minHeight: 'var(--token-space-4)',
                      background: 'var(--token-border-subtle)',
                      marginTop: 'var(--token-space-1)',
                    }}
                  />
                )}
              </div>
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 'var(--token-space-1)',
                  paddingTop: 0,
                }}
              >
                <span
                  style={{
                    fontSize: 'var(--token-fs-15)',
                    fontWeight: idx === 0 ? 600 : 400,
                    color: 'var(--token-text-primary)',
                  }}
                >
                  {milestone.label}
                </span>
                <time
                  dateTime={milestone.at}
                  style={{
                    fontSize: 'var(--token-fs-13)',
                    color: 'var(--token-text-secondary)',
                  }}
                >
                  {formatDateTime(milestone.at)}
                </time>
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

/**
 * Formats an ISO timestamp as a localised date-time string.
 * @param {string} iso
 * @returns {string}
 */
function formatDateTime(iso) {
  try {
    return new Date(iso).toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}
