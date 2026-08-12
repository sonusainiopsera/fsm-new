/**
 * TimeEntryCard — labour time capture for the log-work screen.
 *
 * Supports two modes selectable by the technician:
 * - "duration": explicit minutes (1–1440)
 * - "range": start time + end time (end must be after start)
 *
 * Maintains a stable per-intent idempotency key in state; regenerated only
 * on explicit "submit new entry" action.
 *
 * @module app/technician/components/TimeEntryCard
 */

import React, { useState } from 'react';
import styles from './TimeEntryCard.module.css';

const MAX_NOTE_LENGTH = 500;

/**
 * Validates the labour time form.
 * @param {'duration' | 'range'} mode
 * @param {object} fields
 * @returns {Record<string, string>} map of field name to error message (empty if valid)
 */
export function validateLabourForm(mode, { durationMinutes, startedAt, endedAt, note }) {
  const errors = {};

  if (mode === 'duration') {
    const mins = parseInt(durationMinutes, 10);
    if (!durationMinutes || isNaN(mins) || mins < 1 || mins > 1440) {
      errors.durationMinutes = 'Duration must be between 1 and 1440 minutes.';
    }
  } else {
    if (!startedAt) errors.startedAt = 'Start time is required.';
    if (!endedAt)   errors.endedAt   = 'End time is required.';
    if (startedAt && endedAt && endedAt <= startedAt) {
      errors.endedAt = 'End time must be after start time.';
    }
  }

  if (note && note.length > MAX_NOTE_LENGTH) {
    errors.note = `Note must not exceed ${MAX_NOTE_LENGTH} characters.`;
  }

  return errors;
}

/**
 * @param {{
 *   onSubmit: (entry: {mode: string, durationMinutes: number|null, startedAt: string|null, endedAt: string|null, note: string}) => void,
 *   isPending: boolean,
 *   error: string | null,
 *   submitted: boolean,
 * }} props
 */
export function TimeEntryCard({ onSubmit, isPending, error, submitted }) {
  const [mode, setMode]               = useState('duration');
  const [durationMinutes, setDuration]= useState('');
  const [startedAt, setStartedAt]     = useState('');
  const [endedAt, setEndedAt]         = useState('');
  const [note, setNote]               = useState('');
  const [errors, setErrors]           = useState({});

  function handleSubmit(e) {
    e.preventDefault();
    const validationErrors = validateLabourForm(mode, { durationMinutes, startedAt, endedAt, note });
    setErrors(validationErrors);
    if (Object.keys(validationErrors).length > 0) return;

    onSubmit({
      mode,
      durationMinutes: mode === 'duration' ? parseInt(durationMinutes, 10) : null,
      startedAt: mode === 'range' ? startedAt : null,
      endedAt:   mode === 'range' ? endedAt   : null,
      note: note.trim() || null,
    });
  }

  if (submitted) {
    return (
      <section className={styles.card} aria-label="Labour time">
        <h2 className={styles.heading}>Labour time</h2>
        <p className={styles.submitted} role="status">Time recorded ✓</p>
      </section>
    );
  }

  return (
    <section className={styles.card} aria-label="Labour time">
      <h2 className={styles.heading}>Labour time</h2>

      <div className={styles.modeToggle} role="group" aria-label="Time entry mode">
        <button
          type="button"
          className={`${styles.modeBtn} ${mode === 'duration' ? styles.modeActive : ''}`}
          onClick={() => setMode('duration')}
          aria-pressed={mode === 'duration'}
        >
          Duration
        </button>
        <button
          type="button"
          className={`${styles.modeBtn} ${mode === 'range' ? styles.modeActive : ''}`}
          onClick={() => setMode('range')}
          aria-pressed={mode === 'range'}
        >
          Start / End
        </button>
      </div>

      <form onSubmit={handleSubmit}>
        {mode === 'duration' ? (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="duration-mins">Minutes</label>
            <input
              id="duration-mins"
              type="number"
              className={`${styles.input} ${errors.durationMinutes ? styles.inputError : ''}`}
              value={durationMinutes}
              onChange={(e) => setDuration(e.target.value)}
              min={1}
              max={1440}
              placeholder="e.g. 90"
              disabled={isPending}
              aria-invalid={!!errors.durationMinutes}
              aria-describedby={errors.durationMinutes ? 'duration-err' : undefined}
            />
            {errors.durationMinutes && (
              <p id="duration-err" className={styles.fieldError} role="alert">{errors.durationMinutes}</p>
            )}
          </div>
        ) : (
          <>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="started-at">Start time</label>
              <input
                id="started-at"
                type="datetime-local"
                className={`${styles.input} ${errors.startedAt ? styles.inputError : ''}`}
                value={startedAt}
                onChange={(e) => setStartedAt(e.target.value)}
                disabled={isPending}
                aria-invalid={!!errors.startedAt}
              />
              {errors.startedAt && <p className={styles.fieldError} role="alert">{errors.startedAt}</p>}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ended-at">End time</label>
              <input
                id="ended-at"
                type="datetime-local"
                className={`${styles.input} ${errors.endedAt ? styles.inputError : ''}`}
                value={endedAt}
                onChange={(e) => setEndedAt(e.target.value)}
                disabled={isPending}
                aria-invalid={!!errors.endedAt}
              />
              {errors.endedAt && <p className={styles.fieldError} role="alert">{errors.endedAt}</p>}
            </div>
          </>
        )}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="labour-note">Work performed (optional)</label>
          <textarea
            id="labour-note"
            className={styles.textarea}
            value={note}
            onChange={(e) => setNote(e.target.value.slice(0, MAX_NOTE_LENGTH))}
            rows={2}
            placeholder="Brief description of work performed…"
            disabled={isPending}
            maxLength={MAX_NOTE_LENGTH}
          />
          <p className={styles.charCount}>{note.length}/{MAX_NOTE_LENGTH}</p>
          {errors.note && <p className={styles.fieldError} role="alert">{errors.note}</p>}
        </div>

        {error && <p className={styles.serverError} role="alert">{error}</p>}

        <button
          type="submit"
          className={styles.submitBtn}
          disabled={isPending}
          aria-busy={isPending}
        >
          {isPending ? 'Saving…' : 'Save time'}
        </button>
      </form>
    </section>
  );
}
