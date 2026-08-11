import React, { useId } from 'react';

import { useDensity } from '../../density/DensityContext.js';

import styles from './FormField.module.css';

/**
 * @typedef {{ field: string, message: string, code?: string }} FieldError
 */

/**
 * Wrapper providing label, help text, required indicator and inline error binding.
 * Automatically wires aria-describedby and aria-invalid on the child control.
 *
 * @param {{
 *   label: string,
 *   required?: boolean,
 *   help?: string,
 *   errors?: FieldError[] | string[],
 *   children: React.ReactNode,
 *   className?: string,
 * }} props
 */
export function FormField({ label, required = false, help, errors = [], children, className = '' }) {
  const uid = useId();
  const fieldId = `ff-${uid}`;
  const helpId = help ? `${fieldId}-help` : undefined;
  const errorIds = errors.map((_, i) => `${fieldId}-err-${i}`);
  const describedBy = [helpId, ...errorIds].filter(Boolean).join(' ') || undefined;
  const hasError = errors.length > 0;
  const { density } = useDensity();

  return (
    <div className={[
      styles.field,
      density === 'compact' ? styles.compact : '',
      className,
    ].filter(Boolean).join(' ')}>

      <div className={styles.labelRow}>
        <label className={styles.label} htmlFor={fieldId}>
          {label}
        </label>
        {required && (
          <span className={styles.required} aria-hidden="true" title="Required">*</span>
        )}
      </div>

      {help && (
        <span id={helpId} className={styles.help}>{help}</span>
      )}

      <div className={styles.control}>
        {React.Children.map(children, (child) =>
          React.isValidElement(child)
            ? React.cloneElement(child, {
                id: fieldId,
                'aria-describedby': describedBy,
                'aria-invalid': hasError ? 'true' : undefined,
                'aria-required': required ? 'true' : undefined,
              })
            : child
        )}
      </div>

      {hasError && (
        <ul className={styles.errors} role="list" aria-live="polite">
          {errors.map((err, i) => {
            const msg = typeof err === 'string' ? err : err.message;
            return (
              <li key={i} id={errorIds[i]} className={styles.error} role="alert">
                <span className={styles.errorIcon} aria-hidden="true">✕</span>
                {msg}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
