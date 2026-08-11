/**
 * @fileoverview ToastProvider — queue with aria-live regions.
 * At most one informational toast visible. Danger toasts never auto-dismiss.
 */
import { createContext, useContext, useState, useCallback, useRef, useEffect } from 'react'

/** @typedef {'info' | 'success' | 'warning' | 'danger'} ToastVariant */

/**
 * @typedef {{
 *   id: string,
 *   variant: ToastVariant,
 *   message: string,
 *   autoDismissMs?: number
 * }} ToastItem
 */

const VALID_VARIANTS = /** @type {ToastVariant[]} */ (['info', 'success', 'warning', 'danger'])
const DEFAULT_DISMISS_MS = 5000
let _counter = 0

/** @type {React.Context<{ toast: (msg: string, variant?: ToastVariant, opts?: { autoDismissMs?: number }) => void }>} */
export const ToastContext = createContext({ toast: () => {} })

export function useToast() {
  return useContext(ToastContext)
}

/**
 * @param {{ children: React.ReactNode }} props
 */
export function ToastProvider({ children }) {
  const [queue, setQueue] = useState(/** @type {ToastItem[]} */ ([]))
  const timers = useRef(/** @type {Map<string, ReturnType<typeof setTimeout>>} */ (new Map()))

  const dismiss = useCallback((id) => {
    setQueue(q => q.filter(t => t.id !== id))
    const t = timers.current.get(id)
    if (t) { clearTimeout(t); timers.current.delete(id) }
  }, [])

  const toast = useCallback((message, variant = 'info', opts = {}) => {
    if (!VALID_VARIANTS.includes(variant)) {
      console.warn(`[Toast] Unknown variant "${variant}"`)
      variant = 'info'
    }

    const id = `toast-${++_counter}`
    const item = /** @type {ToastItem} */ ({ id, variant, message })

    setQueue(prev => {
      // Throttle: at most one informational toast visible at a time
      if (variant !== 'danger') {
        const filtered = prev.filter(t => t.variant === 'danger')
        return [...filtered, item]
      }
      return [...prev, item]
    })

    // Auto-dismiss — danger toasts never auto-dismiss
    if (variant !== 'danger') {
      const ms = opts.autoDismissMs ?? DEFAULT_DISMISS_MS
      const timer = setTimeout(() => dismiss(id), ms)
      timers.current.set(id, timer)
    }
  }, [dismiss])

  // Cleanup timers on unmount
  useEffect(() => {
    return () => { timers.current.forEach(t => clearTimeout(t)) }
  }, [])

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}

      {/* Polite region for info/success/warning */}
      <div
        role="status"
        aria-live="polite"
        aria-atomic="false"
        style={regionStyle}
      >
        {queue.filter(t => t.variant !== 'danger').slice(-1).map(t => (
          <Toast key={t.id} item={t} onDismiss={dismiss} />
        ))}
      </div>

      {/* Assertive region for danger */}
      <div
        role="alert"
        aria-live="assertive"
        aria-atomic="true"
        style={{ ...regionStyle, top: undefined, bottom: 'calc(var(--token-space-6) + 60px)' }}
      >
        {queue.filter(t => t.variant === 'danger').map(t => (
          <Toast key={t.id} item={t} onDismiss={dismiss} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}

const regionStyle = {
  position: 'fixed',
  bottom: 'var(--token-space-6)',
  right: 'var(--token-gutter)',
  zIndex: 100,
  display: 'flex',
  flexDirection: 'column',
  gap: 'var(--token-space-3)',
  pointerEvents: 'none',
}

const VARIANT_STYLES = {
  info:    { bg: 'var(--token-info-subtle)',    text: 'var(--token-info-emphasis)',    border: 'var(--token-info-default)',    icon: 'ℹ' },
  success: { bg: 'var(--token-success-subtle)', text: 'var(--token-success-emphasis)', border: 'var(--token-success-default)', icon: '✓' },
  warning: { bg: 'var(--token-warning-subtle)', text: 'var(--token-warning-emphasis)', border: 'var(--token-warning-default)', icon: '⚠' },
  danger:  { bg: 'var(--token-danger-subtle)',  text: 'var(--token-danger-emphasis)',  border: 'var(--token-danger-default)',  icon: '!' },
}

/**
 * @param {{ item: ToastItem, onDismiss: (id: string) => void }} props
 */
function Toast({ item, onDismiss }) {
  const s = VARIANT_STYLES[item.variant] ?? VARIANT_STYLES.info
  return (
    <div
      data-toast-variant={item.variant}
      style={{
        pointerEvents: 'auto',
        display: 'flex',
        alignItems: 'flex-start',
        gap: 'var(--token-space-3)',
        padding: 'var(--token-space-3) var(--token-space-4)',
        background: s.bg,
        color: s.text,
        border: `1px solid ${s.border}`,
        borderRadius: 'var(--token-radius-card)',
        fontSize: 'var(--token-fs-14)',
        fontFamily: 'var(--token-family-base)',
        maxWidth: '360px',
        boxShadow: 'var(--token-elevation-1)',
      }}
    >
      <span aria-hidden="true" style={{ flexShrink: 0, marginTop: '1px' }}>{s.icon}</span>
      <span style={{ flex: 1 }}>{item.message}</span>
      <button
        type="button"
        aria-label="Dismiss notification"
        onClick={() => onDismiss(item.id)}
        style={{
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          padding: 0,
          color: s.text,
          opacity: 0.7,
          fontSize: 'var(--token-fs-14)',
          lineHeight: 1,
          flexShrink: 0,
        }}
      >
        ✕
      </button>
    </div>
  )
}
