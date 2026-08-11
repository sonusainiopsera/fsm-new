import React, { useEffect, useRef, useCallback, useId } from 'react';
import { createPortal } from 'react-dom';

import styles from './Modal.module.css';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * @param {HTMLElement} container
 * @returns {HTMLElement[]}
 */
function getFocusable(container) {
  return Array.from(container.querySelectorAll(FOCUSABLE));
}

/**
 * Modal overlay with focus trap, Escape to close, focus restoration, and accessible naming.
 * Shares elevation-2 with DetailDrawer — two-elevation ceiling holds.
 *
 * @param {{
 *   open: boolean,
 *   title: string,
 *   onClose: () => void,
 *   children: React.ReactNode,
 *   footer?: React.ReactNode,
 * }} props
 */
export function Modal({ open, title, onClose, children, footer }) {
  const panelRef = useRef(null);
  const triggerRef = useRef(null);
  const titleId = useId();

  useEffect(() => {
    if (open) {
      triggerRef.current = document.activeElement;
    }
  }, [open]);

  useEffect(() => {
    if (!open) {
      if (triggerRef.current && typeof triggerRef.current.focus === 'function') {
        triggerRef.current.focus();
        triggerRef.current = null;
      }
      return;
    }
    const el = panelRef.current;
    if (!el) return;
    const focusable = getFocusable(el);
    if (focusable.length > 0) focusable[0].focus();
  }, [open]);

  const handleKeyDown = useCallback((e) => {
    if (!panelRef.current) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
      return;
    }
    if (e.key === 'Tab') {
      const focusable = getFocusable(panelRef.current);
      if (focusable.length === 0) { e.preventDefault(); return; }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey) {
        if (document.activeElement === first) { e.preventDefault(); last.focus(); }
      } else {
        if (document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    }
  }, [onClose]);

  useEffect(() => {
    if (open) {
      document.addEventListener('keydown', handleKeyDown);
    }
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, handleKeyDown]);

  if (!open) return null;

  return createPortal(
    <div
      className={styles.scrim}
      role="presentation"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={styles.panel}
      >
        <div className={styles.header}>
          <h2 id={titleId} className={styles.title}>{title}</h2>
          <button type="button" className={styles.closeBtn} onClick={onClose} aria-label="Close dialog">
            ✕
          </button>
        </div>
        <div className={styles.body}>{children}</div>
        {footer && <div className={styles.footer}>{footer}</div>}
      </div>
    </div>,
    document.body
  );
}
