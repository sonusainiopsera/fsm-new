import React from 'react';
import { FormField } from '../../../components/FormField/FormField.jsx';
import styles from './AppointmentImpactAcknowledgement.module.css';

const MAX_LENGTH = 2000;

/**
 * Acknowledgement step revealed only when the server returns a
 * CONFIRMED_APPOINTMENT_BREACH 422.  The dialog never auto-fills this field.
 *
 * @param {{
 *   value: string,
 *   onChange: (v: string) => void,
 *   errors?: Array<string | { field: string, message: string }>,
 * }} props
 */
export function AppointmentImpactAcknowledgement({ value, onChange, errors = [] }) {
  const remaining = MAX_LENGTH - (value?.length ?? 0);

  return (
    <div className={styles.wrapper}>
      <div className={styles.banner} role="alert">
        <span className={styles.icon} aria-hidden="true">⚠</span>
        <p className={styles.message}>
          This work order has a confirmed customer appointment window.
          Reassigning the technician will breach that commitment.
          You must provide an explicit acknowledgement before continuing.
        </p>
      </div>

      <FormField
        label="Appointment impact acknowledgement"
        required
        help="Explain how the appointment breach will be managed, e.g. 'Customer notified and accepted rescheduling to 14:00.'"
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
