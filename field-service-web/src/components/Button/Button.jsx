import React from 'react';

import styles from './Button.module.css';

/**
 * @typedef {'primary' | 'secondary' | 'tertiary' | 'ghost' | 'destructive'} ButtonVariant
 */

const VALID_VARIANTS = ['primary', 'secondary', 'tertiary', 'ghost', 'destructive'];

/**
 * @param {string} variant
 * @returns {void}
 */
function assertVariant(variant) {
  if (!VALID_VARIANTS.includes(variant)) {
    console.warn(`Button: unknown variant "${variant}". Expected one of: ${VALID_VARIANTS.join(', ')}`);
  }
}

/**
 * Accessible button primitive.
 *
 * @param {{
 *   variant?: ButtonVariant,
 *   touch?: boolean,
 *   loading?: boolean,
 *   disabled?: boolean,
 *   onClick?: (e: React.MouseEvent) => void,
 *   type?: 'button' | 'submit' | 'reset',
 *   children: React.ReactNode,
 *   className?: string,
 *   'aria-label'?: string,
 * }} props
 */
export function Button({
  variant = 'primary',
  touch = false,
  loading = false,
  disabled = false,
  onClick,
  type = 'button',
  children,
  className = '',
  ...rest
}) {
  assertVariant(variant);

  const cls = [
    styles.btn,
    styles[variant],
    touch ? styles.touch : '',
    className,
  ].filter(Boolean).join(' ');

  return (
    <button
      type={type}
      className={cls}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      onClick={onClick}
      {...rest}
    >
      {loading && <span className={styles.spinner} aria-hidden="true" />}
      {children}
    </button>
  );
}
