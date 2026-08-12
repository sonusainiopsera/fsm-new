import React from 'react';
import { FormField } from '../../../components/FormField/FormField.jsx';
import styles from './OverrideReasonField.module.css';

const MAX_LENGTH = 1000;

/**
 * Reason textarea required when the selected technician is outside the top 3
 * or not present in the recommendation snapshot.
 *
 * @param {{
 *   value: string,
 *   onChange: (v: string) => void,
 *   errors?: Array<string | { field: string, message: string }>,
 * }} props
 */
export function OverrideReasonField({ value, onChange, errors = [] }) {
  const remaining = MAX_LENGTH - (value?.length ?? 0);

  return (
    <div className={styles.wrapper}>
      <FormField
        label="Override reason"
        required
        help="This technician is outside the top 3. Explain why they were selected."
        errors={errors}
      >
        <textarea
          value={value ?? ''}
          onChange={e => onChange(e.target.value)}
          maxLength={MAX_LENGTH}
          rows={3}
          className={styles.textarea}
        />
      </FormField>
      <p className={styles.charCount} aria-live="polite">
        {remaining} character{remaining !== 1 ? 's' : ''} remaining
      </p>
    </div>
  );
}
