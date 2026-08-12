import React from 'react';
import styles from './WindowSelector.module.css';

/** @type {Array<{ value: string, label: string }>} */
const WINDOWS = [
  { value: '7d',  label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: '90d', label: '90 days' },
];

/**
 * Window selector — 7 / 30 / 90 days, URL-synchronized.
 *
 * @param {{
 *   value: string,
 *   onChange: (window: string) => void,
 * }} props
 */
export function WindowSelector({ value, onChange }) {
  return (
    <div className={styles.group} role="group" aria-label="Time window">
      {WINDOWS.map((w) => (
        <button
          key={w.value}
          type="button"
          className={[styles.btn, value === w.value ? styles.active : ''].filter(Boolean).join(' ')}
          aria-pressed={value === w.value}
          onClick={() => onChange(w.value)}
        >
          {w.label}
        </button>
      ))}
    </div>
  );
}
