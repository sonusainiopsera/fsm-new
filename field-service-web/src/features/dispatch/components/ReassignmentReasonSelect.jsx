import React from 'react';
import { FormField } from '../../../components/FormField/FormField.jsx';
import styles from './ReassignmentReasonSelect.module.css';

const MAX_NOTES_LENGTH = 2000;

/** Controlled list matching the server ReassignmentReason enum (WO-139). */
const REASSIGNMENT_REASONS = [
  { value: 'TECHNICIAN_UNAVAILABLE', label: 'Technician unavailable' },
  { value: 'JOB_OVERRUN',            label: 'Job overrun' },
  { value: 'SKILL_MISMATCH',         label: 'Skill mismatch' },
  { value: 'SLA_RISK',               label: 'SLA risk' },
  { value: 'CUSTOMER_REQUEST',       label: 'Customer request' },
  { value: 'PARTS_UNAVAILABLE',      label: 'Parts unavailable' },
  { value: 'OTHER',                  label: 'Other' },
];

/**
 * Reassignment reason select with optional supplementary notes.
 *
 * @param {{
 *   value: string,
 *   onChange: (v: string) => void,
 *   notes: string,
 *   onNotesChange: (v: string) => void,
 *   errors?: Array<string | { field: string, message: string }>,
 * }} props
 */
export function ReassignmentReasonSelect({ value, onChange, notes, onNotesChange, errors = [] }) {
  const notesRemaining = MAX_NOTES_LENGTH - (notes?.length ?? 0);

  return (
    <div className={styles.wrapper}>
      <FormField label="Reassignment reason" required errors={errors}>
        <select value={value ?? ''} onChange={e => onChange(e.target.value)} className={styles.select}>
          <option value="" disabled>Select a reason…</option>
          {REASSIGNMENT_REASONS.map(r => (
            <option key={r.value} value={r.value}>{r.label}</option>
          ))}
        </select>
      </FormField>

      <FormField
        label="Supplementary notes"
        help="Optional. Cannot substitute for the coded reason above."
      >
        <textarea
          value={notes ?? ''}
          onChange={e => onNotesChange(e.target.value)}
          maxLength={MAX_NOTES_LENGTH}
          rows={3}
          className={styles.textarea}
        />
      </FormField>
      <p className={styles.charCount} aria-live="polite">
        {notesRemaining} character{notesRemaining !== 1 ? 's' : ''} remaining
      </p>
    </div>
  );
}
