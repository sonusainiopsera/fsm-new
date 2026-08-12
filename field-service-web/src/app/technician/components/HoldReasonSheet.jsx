/**
 * @fileoverview HoldReasonSheet — controlled-vocabulary hold reason picker (WO-156 AC-7).
 *
 * Rules:
 * - Reason must be chosen from the server-supplied list (never free-text only).
 * - Supplementary note is optional, capped at 500 characters.
 * - Submitting without a listed reason is blocked client-side (validated on submit).
 * - If holdReasons is empty/unavailable the submit button is disabled with an explanation.
 */
import { useState } from 'react'
import styles from './HoldReasonSheet.module.css'

const NOTE_MAX_LEN = 500

/**
 * @param {{
 *   holdReasons: Array<{ code: string, label: string }>,
 *   onSubmit: (reasonCode: string, note: string) => void,
 *   onCancel: () => void,
 *   isPending: boolean,
 *   error?: string | null,
 * }} props
 */
export function HoldReasonSheet({
  holdReasons = [],
  onSubmit,
  onCancel,
  isPending = false,
  error = null,
  initialCode = '',
  initialNote = '',
}) {
  const [selectedCode, setSelectedCode] = useState(initialCode)
  const [note, setNote] = useState(initialNote.slice(0, NOTE_MAX_LEN))
  const [validationError, setValidationError] = useState(null)

  const reasonsUnavailable = holdReasons.length === 0

  function handleSubmit(e) {
    e.preventDefault()
    if (!selectedCode) {
      setValidationError('Please select a reason before placing the job on hold.')
      return
    }
    setValidationError(null)
    onSubmit(selectedCode, note.trim())
  }

  return (
    <div
      className={styles.sheet}
      role="dialog"
      aria-modal="true"
      aria-label="Place job on hold"
      data-testid="hold-reason-sheet"
    >
      <div className={styles.header}>
        <h2 className={styles.title}>Place job on hold</h2>
        <button
          type="button"
          className={styles.closeButton}
          onClick={onCancel}
          aria-label="Close"
          data-testid="hold-reason-close"
        >
          ✕
        </button>
      </div>

      {reasonsUnavailable ? (
        <p className={styles.unavailableNotice} role="status">
          Hold reasons are temporarily unavailable. Please try again shortly.
        </p>
      ) : (
        <form onSubmit={handleSubmit} noValidate className={styles.form}>
          <div className={styles.field}>
            <label htmlFor="hold-reason-select" className={styles.label}>
              Reason <span aria-hidden="true">*</span>
            </label>
            <select
              id="hold-reason-select"
              className={styles.select}
              value={selectedCode}
              onChange={(e) => { setSelectedCode(e.target.value); setValidationError(null) }}
              disabled={isPending}
              aria-required="true"
              data-testid="hold-reason-select"
            >
              <option value="">Select a reason…</option>
              {holdReasons.map(({ code, label }) => (
                <option key={code} value={code}>{label}</option>
              ))}
            </select>
          </div>

          <div className={styles.field}>
            <label htmlFor="hold-reason-note" className={styles.label}>
              Additional note (optional)
            </label>
            <textarea
              id="hold-reason-note"
              className={styles.textarea}
              value={note}
              onChange={(e) => setNote(e.target.value.slice(0, NOTE_MAX_LEN))}
              disabled={isPending}
              rows={3}
              maxLength={NOTE_MAX_LEN}
              aria-describedby="hold-note-counter"
              data-testid="hold-reason-note"
            />
            <span id="hold-note-counter" className={styles.counter}>
              {note.length}/{NOTE_MAX_LEN}
            </span>
          </div>

          {(validationError || error) && (
            <p className={styles.error} role="alert" data-testid="hold-reason-error">
              {validationError ?? error}
            </p>
          )}

          <div className={styles.actions}>
            <button
              type="button"
              className={styles.cancelButton}
              onClick={onCancel}
              disabled={isPending}
              data-testid="hold-cancel"
            >
              Cancel
            </button>
            <button
              type="submit"
              className={styles.submitButton}
              disabled={isPending || !selectedCode}
              aria-busy={isPending}
              data-testid="hold-submit"
            >
              {isPending ? 'Submitting…' : 'Place on hold'}
            </button>
          </div>
        </form>
      )}
    </div>
  )
}
