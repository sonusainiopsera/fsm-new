import React, { createContext, useContext, useCallback, useReducer } from 'react';

import styles from './Toast.module.css';

/**
 * @typedef {'info' | 'success' | 'warning' | 'danger'} ToastVariant
 *
 * @typedef {{
 *   id: string,
 *   variant: ToastVariant,
 *   message: string,
 *   detail?: string,
 * }} Toast
 */

const ICONS = { info: 'ℹ', success: '✓', warning: '⚠', danger: '✕' };
const VARIANT_CLASS = {
  info: styles.toastInfo,
  success: styles.toastSuccess,
  warning: styles.toastWarning,
  danger: styles.toastDanger,
};

let counter = 0;
function nextId() { return `toast-${++counter}`; }

/**
 * @typedef {{
 *   show: (opts: { variant?: ToastVariant, message: string, detail?: string }) => void,
 *   dismiss: (id: string) => void,
 * }} ToastContextValue
 */

/** @type {import('react').Context<ToastContextValue>} */
const ToastContext = createContext({ show: () => {}, dismiss: () => {} });

/**
 * @param {Toast[]} state
 * @param {{ type: 'add', toast: Toast } | { type: 'remove', id: string }} action
 * @returns {Toast[]}
 */
function reducer(state, action) {
  if (action.type === 'add') {
    const toast = action.toast;
    if (toast.variant !== 'danger') {
      const withoutInfo = state.filter((t) => t.variant !== toast.variant);
      return [...withoutInfo, toast];
    }
    return [...state, toast];
  }
  if (action.type === 'remove') {
    return state.filter((t) => t.id !== action.id);
  }
  return state;
}

const AUTO_DISMISS_MS = 5000;

/**
 * Provides toast notifications throughout the tree.
 * At most one visible informational toast at a time.
 * Danger toasts never auto-dismiss.
 *
 * @param {{ children: React.ReactNode }} props
 */
export function ToastProvider({ children }) {
  const [toasts, dispatch] = useReducer(reducer, []);

  const dismiss = useCallback((id) => {
    dispatch({ type: 'remove', id });
  }, []);

  const show = useCallback(({ variant = 'info', message, detail }) => {
    const id = nextId();
    dispatch({ type: 'add', toast: { id, variant, message, detail } });

    if (variant !== 'danger') {
      setTimeout(() => dispatch({ type: 'remove', id }), AUTO_DISMISS_MS);
    }
  }, []);

  return (
    <ToastContext.Provider value={{ show, dismiss }}>
      {children}
      <div
        className={styles.region}
        aria-live={toasts.some((t) => t.variant === 'danger') ? 'assertive' : 'polite'}
        aria-atomic="false"
        role="region"
        aria-label="Notifications"
      >
        {toasts.map((toast) => (
          <div
            key={toast.id}
            role={toast.variant === 'danger' ? 'alert' : 'status'}
            className={[styles.toast, VARIANT_CLASS[toast.variant]].filter(Boolean).join(' ')}
          >
            <span className={styles.icon} aria-hidden="true">{ICONS[toast.variant]}</span>
            <div className={styles.content}>
              <p className={styles.message}>{toast.message}</p>
              {toast.detail && <p className={styles.detail}>{toast.detail}</p>}
            </div>
            <button
              type="button"
              className={styles.dismiss}
              onClick={() => dismiss(toast.id)}
              aria-label={`Dismiss: ${toast.message}`}
            >
              ✕
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/**
 * @returns {ToastContextValue}
 */
export function useToast() {
  return useContext(ToastContext);
}
