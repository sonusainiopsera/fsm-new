/**
 * @fileoverview Modal and shared overlay primitive — focus trap, Escape-to-close, aria naming.
 * Two-elevation ceiling enforced (elevation-2). Shared with DetailDrawer.
 */
import { useEffect, useRef } from 'react'

const FOCUSABLE = [
  'a[href]', 'button:not([disabled])', 'input:not([disabled])',
  'select:not([disabled])', 'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

/**
 * @param {HTMLElement} container
 */
function trapFocus(container) {
  const els = /** @type {HTMLElement[]} */ ([...container.querySelectorAll(FOCUSABLE)])
  if (!els.length) return () => {}

  const first = els[0]
  const last = els[els.length - 1]

  function handler(e) {
    if (e.key !== 'Tab') return
    if (e.shiftKey) {
      if (document.activeElement === first) { e.preventDefault(); last.focus() }
    } else {
      if (document.activeElement === last) { e.preventDefault(); first.focus() }
    }
  }
  container.addEventListener('keydown', handler)
  // Focus first element
  first.focus()
  return () => container.removeEventListener('keydown', handler)
}

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   title: string,
 *   children: React.ReactNode,
 *   size?: 'sm' | 'md' | 'lg'
 * }} props
 */
export function Modal({ open, onClose, title, children, size = 'md' }) {
  const panelRef = useRef(/** @type {HTMLDivElement | null} */ (null))
  const triggerRef = useRef(/** @type {Element | null} */ (null))

  useEffect(() => {
    if (open) {
      triggerRef.current = document.activeElement
      const cleanup = panelRef.current ? trapFocus(panelRef.current) : () => {}
      return cleanup
    } else {
      if (triggerRef.current instanceof HTMLElement) {
        triggerRef.current.focus()
      }
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    function handleKey(e) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [open, onClose])

  if (!open) return null

  const widths = { sm: '400px', md: '560px', lg: '800px' }

  return (
    <div
      role="presentation"
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 50,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 'var(--token-space-4)',
      }}
    >
      {/* Scrim */}
      <div
        aria-hidden="true"
        onClick={onClose}
        style={{
          position: 'absolute',
          inset: 0,
          background: 'var(--token-elevation-scrim)',
        }}
      />

      {/* Panel */}
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        style={{
          position: 'relative',
          background: 'var(--token-surface-overlay)',
          borderRadius: 'var(--token-radius-overlay)',
          boxShadow: 'var(--token-elevation-2)',
          width: '100%',
          maxWidth: widths[size],
          maxHeight: '90vh',
          overflow: 'auto',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {/* Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: 'var(--token-space-4) var(--token-space-6)',
            borderBottom: 'var(--token-elevation-border)',
          }}
        >
          <h2
            id="modal-title"
            style={{
              margin: 0,
              fontSize: 'var(--token-fs-20)',
              fontFamily: 'var(--token-family-base)',
              color: 'var(--token-text-primary)',
              fontWeight: 600,
            }}
          >
            {title}
          </h2>
          <button
            type="button"
            aria-label="Close dialog"
            onClick={onClose}
            style={{
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              padding: 'var(--token-space-2)',
              borderRadius: 'var(--token-radius-control)',
              color: 'var(--token-text-secondary)',
              fontSize: 'var(--token-fs-20)',
              lineHeight: 1,
              display: 'flex',
              alignItems: 'center',
            }}
          >
            ✕
          </button>
        </div>

        {/* Body */}
        <div style={{ padding: 'var(--token-space-6)', flex: 1, overflowY: 'auto' }}>
          {children}
        </div>
      </div>
    </div>
  )
}
