/**
 * HoldReasonSheet — bottom sheet for selecting a hold reason before placing a job on hold.
 *
 * Driven by the server-supplied holdReasons vocabulary; never hardcodes reason codes.
 * Free text supplements but never replaces the coded reason.
 * If holdReasons is empty (temporarily unavailable) the HOLD action is disabled.
 *
 * @module app/technician/components/HoldReasonSheet
 */

import React, { useState, useEffect } from 'react';
import styles from './HoldReasonSheet.module.css';

const NOTE_MAX_LENGTH = 500;

/**
 * @param {{
 *   open: boolean,
 *   holdReasons: Array<{code: string, label: string, sortOrder: number}>,
 *   onConfirm: (reasonCode: string, note: string) => void,
 *   onCancel: () => void,
 *   isPending: boolean,
 * }} props
 */
export function HoldReasonSheet({ open, holdReasons, onConfirm, onCancel, isPending }) {
  const [selectedCode, setSelectedCode] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) {
      setSelectedCode('');
      setNote('');
      setError('');
    }
  }, [open]);

  if (!open) return null;

  function handleSubmit(e) {
    e.preventDefault();
    if (!selectedCode) {
      setError('Please select a reason before placing on hold.');
      return;
    }
    setError('');
    onConfirm(selectedCode, note.trim());
  }

  return (
    <div
      className={styles.overlay}
      role="dialog"
      aria-modal="true"
      aria-labelledby="hold-sheet-title"
    >
      <div className={styles.sheet}>
        <h2 id="hold-sheet-title" className={styles.title}>Place on hold</h2>

        <form onSubmit={handleSubmit}>
          <fieldset className={styles.fieldset} disabled={isPending}>
            <legend className={styles.legend}>Reason (required)</legend>
            {holdReasons.map((r) => (
              <label key={r.code} className={styles.radioLabel}>
                <input
                  type="radio"
                  name="holdReason"
                  value={r.code}
                  checked={selectedCode === r.code}
                  onChange={() => { setSelectedCode(r.code); setError(''); }}
                  className={styles.radio}
                />
                {r.label}
              </label>
            ))}
          </fieldset>

          {error && (
            <p className={styles.error} role="alert">{error}</p>
          )}

          <label className={styles.noteLabel} htmlFor="hold-note">
            Additional notes (optional)
          </label>
          <textarea
            id="hold-note"
            className={styles.textarea}
            value={note}
            onChange={(e) => setNote(e.target.value.slice(0, NOTE_MAX_LENGTH))}
            maxLength={NOTE_MAX_LENGTH}
            rows={3}
            placeholder="Optional additional context…"
            disabled={isPending}
          />
          <p className={styles.charCount}>{note.length}/{NOTE_MAX_LENGTH}</p>

          <div className={styles.actions}>
            <button
              type="button"
              className={styles.cancelBtn}
              onClick={onCancel}
              disabled={isPending}
            >
              Cancel
            </button>
            <button
              type="submit"
              className={styles.confirmBtn}
              disabled={isPending || !selectedCode}
              aria-busy={isPending}
            >
              {isPending ? 'Placing on hold…' : 'Place on hold'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
