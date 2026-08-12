/**
 * @fileoverview ReassignmentReasonSelect — controlled coded-reason selector.
 *
 * Required in reassign mode. Supplements with optional free-text notes
 * that cannot substitute for the coded reason selection.
 */
import { REASSIGNMENT_REASONS, REASON_NOTES_MAX_LEN } from '../api/useAssignTechnician.js'

const SELECT_ID = 'reassignment-reason'
const SELECT_ERROR_ID = 'reassignment-reason-error'
const NOTES_ID = 'reassignment-reason-notes'

/**
 * @param {{
 *   reason: string,
 *   notes: string,
 *   onReasonChange: (val: string) => void,
 *   onNotesChange: (val: string) => void,
 *   touched?: boolean,
 *   disabled?: boolean
 * }} props
 */
export function ReassignmentReasonSelect({
  reason,
  notes,
  onReasonChange,
  onNotesChange,
  touched = false,
  disabled = false,
}) {
  const hasError = touched && reason === ''
  const notesRemaining = REASON_NOTES_MAX_LEN - notes.length

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
      {/* Coded reason select */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-2)' }}>
        <label
          htmlFor={SELECT_ID}
          style={{
            fontSize: 'var(--token-fs-13)',
            fontWeight: 600,
            color: 'var(--token-text-primary)',
            fontFamily: 'var(--token-family-base)',
          }}
        >
          Reassignment reason
          <span aria-hidden="true" style={{ color: 'var(--token-danger-default)', marginLeft: '0.2em' }}>*</span>
        </label>

        <select
          id={SELECT_ID}
          name="reassignmentReason"
          disabled={disabled}
          value={reason}
          onChange={(e) => onReasonChange(e.target.value)}
          aria-required="true"
          aria-invalid={hasError ? 'true' : 'false'}
          aria-describedby={hasError ? SELECT_ERROR_ID : undefined}
          style={{
            width: '100%',
            padding: 'var(--token-space-3)',
            fontFamily: 'var(--token-family-base)',
            fontSize: 'var(--token-fs-14)',
            color: reason === '' ? 'var(--token-text-secondary)' : 'var(--token-text-primary)',
            background: 'var(--token-surface-input)',
            border: `1px solid ${hasError ? 'var(--token-danger-default)' : 'var(--token-border-default)'}`,
            borderRadius: 'var(--token-radius-control)',
            appearance: 'auto',
            cursor: disabled ? 'default' : 'pointer',
          }}
        >
          <option value="" disabled>Select a reason…</option>
          {REASSIGNMENT_REASONS.map((r) => (
            <option key={r.value} value={r.value}>
              {r.label}
            </option>
          ))}
        </select>

        {hasError && (
          <span
            id={SELECT_ERROR_ID}
            role="alert"
            style={{
              fontSize: 'var(--token-fs-12)',
              color: 'var(--token-danger-default)',
              fontFamily: 'var(--token-family-base)',
            }}
          >
            Reassignment reason is required.
          </span>
        )}
      </div>

      {/* Optional supplementary notes */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-2)' }}>
        <label
          htmlFor={NOTES_ID}
          style={{
            fontSize: 'var(--token-fs-13)',
            fontWeight: 600,
            color: 'var(--token-text-primary)',
            fontFamily: 'var(--token-family-base)',
          }}
        >
          Additional notes
          <span
            style={{
              fontWeight: 400,
              color: 'var(--token-text-secondary)',
              marginLeft: 'var(--token-space-2)',
              fontSize: 'var(--token-fs-12)',
            }}
          >
            (optional)
          </span>
        </label>
        <textarea
          id={NOTES_ID}
          name="reasonNotes"
          rows={2}
          disabled={disabled}
          value={notes}
          onChange={(e) => onNotesChange(e.target.value)}
          maxLength={REASON_NOTES_MAX_LEN}
          aria-describedby="reassignment-notes-hint"
          style={{
            width: '100%',
            padding: 'var(--token-space-3)',
            fontFamily: 'var(--token-family-base)',
            fontSize: 'var(--token-fs-14)',
            color: 'var(--token-text-primary)',
            background: 'var(--token-surface-input)',
            border: '1px solid var(--token-border-default)',
            borderRadius: 'var(--token-radius-control)',
            resize: 'vertical',
            boxSizing: 'border-box',
          }}
          placeholder="Optional context that supplements the coded reason…"
        />
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            fontFamily: 'var(--token-family-base)',
          }}
        >
          <span
            id="reassignment-notes-hint"
            style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}
          >
            Notes do not replace the coded reason above.
          </span>
          <span
            aria-live="polite"
            aria-label={`${notesRemaining} characters remaining`}
            style={{
              fontSize: 'var(--token-fs-12)',
              color: notesRemaining < 0 ? 'var(--token-danger-default)' : 'var(--token-text-secondary)',
              fontVariantNumeric: 'var(--token-numeric)',
            }}
          >
            {notesRemaining}
          </span>
        </div>
      </div>
    </div>
  )
}
