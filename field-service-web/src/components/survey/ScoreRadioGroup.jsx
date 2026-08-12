/**
 * @fileoverview ScoreRadioGroup — accessible 1-to-5 satisfaction score control.
 *
 * Implemented as a labelled radio group (not an unlabelled icon row) so:
 *   - Each option has a visible text label and correct accessible name.
 *   - Keyboard navigation uses arrow keys within the group (native radio behaviour).
 *   - Screen readers announce the group label and each option's value and label.
 *   - Minimum 44×44 CSS pixel tap targets on each label (WO-175 AC-6).
 */

const SCORE_OPTIONS = [
  { value: 1, label: '1 — Very dissatisfied' },
  { value: 2, label: '2 — Dissatisfied' },
  { value: 3, label: '3 — Neutral' },
  { value: 4, label: '4 — Satisfied' },
  { value: 5, label: '5 — Very satisfied' },
]

/**
 * @param {{
 *   name?: string,
 *   value: number | null,
 *   onChange: (score: number) => void,
 *   disabled?: boolean,
 *   legendId?: string,
 *   error?: string | null
 * }} props
 */
export function ScoreRadioGroup({
  name = 'score',
  value,
  onChange,
  disabled = false,
  legendId,
  error = null,
}) {
  const groupId = legendId ?? 'score-group-legend'

  return (
    <fieldset
      role="radiogroup"
      aria-labelledby={groupId}
      aria-describedby={error ? `${groupId}-error` : undefined}
      style={{
        border: 'none',
        padding: 0,
        margin: 0,
      }}
    >
      <legend
        id={groupId}
        style={{
          fontSize: 'var(--token-fs-15)',
          fontWeight: 600,
          color: 'var(--token-text-primary)',
          marginBottom: 'var(--token-space-3)',
          padding: 0,
        }}
      >
        Overall satisfaction
        <span aria-hidden="true" style={{ color: 'var(--token-danger-default)', marginLeft: 4 }}>*</span>
      </legend>

      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 'var(--token-space-2)',
        }}
      >
        {SCORE_OPTIONS.map(({ value: optVal, label }) => (
          <label
            key={optVal}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 'var(--token-space-3)',
              fontSize: 'var(--token-fs-15)',
              color: disabled ? 'var(--token-text-disabled)' : 'var(--token-text-primary)',
              cursor: disabled ? 'default' : 'pointer',
              minHeight: '44px',
              padding: 'var(--token-space-1) 0',
            }}
          >
            <input
              type="radio"
              name={name}
              value={String(optVal)}
              checked={value === optVal}
              onChange={() => onChange(optVal)}
              disabled={disabled}
              aria-label={label}
              style={{
                width: 20,
                height: 20,
                cursor: disabled ? 'default' : 'pointer',
                flexShrink: 0,
              }}
            />
            {label}
          </label>
        ))}
      </div>

      {error && (
        <p
          id={`${groupId}-error`}
          role="alert"
          style={{
            marginTop: 'var(--token-space-2)',
            fontSize: 'var(--token-fs-13)',
            color: 'var(--token-danger-emphasis)',
          }}
        >
          {error}
        </p>
      )}
    </fieldset>
  )
}
