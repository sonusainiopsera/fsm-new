/**
 * @fileoverview TransitionActionBar — server-driven work order action bar.
 *
 * Renders exactly the actions present in the server's {@code allowedTransitions} array.
 * No transition event is ever hardcoded in the client (WO-156 AC-3).
 *
 * Behaviour:
 * - Disabled with a spinner while an action is in flight (AC-9).
 * - Stable Idempotency-Key generated once per "tap intent" (user gesture), not per render,
 *   so a retry on a flaky link cannot double-apply the transition (AC-4).
 * - 44 px minimum touch targets on all buttons (AC-9).
 * - Empty allowedTransitions renders a read-only "No actions available" state (AC-3).
 * - HOLD action opens the HoldReasonSheet rather than posting directly.
 */
import { useCallback, useRef, useState } from 'react'
import { mapApiError } from '../../../shared/api/errorMapping.js'
import styles from './TransitionActionBar.module.css'

// ── Label / icon map ──────────────────────────────────────────────────────────

const EVENT_META = {
  ASSIGN:   { label: 'Assign',       icon: '✓',  primary: false },
  DEPART:   { label: 'Depart',       icon: '🚗', primary: true  },
  START:    { label: 'Start work',   icon: '▶',  primary: true  },
  COMPLETE: { label: 'Complete',     icon: '✓',  primary: true  },
  HOLD:     { label: 'Put on hold',  icon: '⏸', primary: false },
  RESUME:   { label: 'Resume',       icon: '▶',  primary: true  },
  CLOSE:    { label: 'Close',        icon: '☑',  primary: false },
  CANCEL:   { label: 'Cancel job',   icon: '✕',  primary: false },
}

function metaFor(event) {
  return EVENT_META[event] ?? { label: event, icon: '•', primary: false }
}

// ── Idempotency key generation ────────────────────────────────────────────────

function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return Array.from({ length: 32 }, () =>
    Math.floor(Math.random() * 16).toString(16)
  ).join('')
}

// ── Component ─────────────────────────────────────────────────────────────────

/**
 * @param {{
 *   workOrderId: string,
 *   allowedTransitions: string[],
 *   onTransitionSuccess: (result: unknown) => void,
 *   onConflict: (mappedError: import('../../../shared/api/errorMapping.js').MappedError) => void,
 *   onGuardRefusal: (mappedError: import('../../../shared/api/errorMapping.js').MappedError) => void,
 *   onHoldRequest: () => void,
 *   postTransition: (workOrderId: string, event: string, idempotencyKey: string) => Promise<unknown>
 * }} props
 */
export function TransitionActionBar({
  workOrderId,
  allowedTransitions = [],
  onTransitionSuccess,
  onConflict,
  onGuardRefusal,
  onHoldRequest,
  postTransition,
}) {
  const [inFlight, setInFlight] = useState(false)
  const [errorMessage, setErrorMessage] = useState(null)
  // One key per user tap intent — stable across retries
  const idempotencyKeyRef = useRef(null)

  const handleTap = useCallback(async (event) => {
    if (inFlight) return

    // HOLD is special — opens the reason sheet
    if (event === 'HOLD') {
      onHoldRequest?.()
      return
    }

    // Generate a new idempotency key for this distinct user intent
    idempotencyKeyRef.current = newIdempotencyKey()
    const key = idempotencyKeyRef.current

    setInFlight(true)
    setErrorMessage(null)

    try {
      const result = await postTransition(workOrderId, event, key)
      onTransitionSuccess?.(result)
    } catch (err) {
      const mapped = mapApiError(err)
      if (mapped.kind === 'conflict-refresh') {
        onConflict?.(mapped)
      } else if (mapped.kind === 'guard-message') {
        onGuardRefusal?.(mapped)
        setErrorMessage(mapped.message)
      } else {
        setErrorMessage(mapped.message)
      }
    } finally {
      setInFlight(false)
    }
  }, [inFlight, workOrderId, onTransitionSuccess, onConflict, onGuardRefusal, onHoldRequest, postTransition])

  if (allowedTransitions.length === 0) {
    return (
      <div className={styles.bar} data-testid="transition-action-bar">
        <span className={styles.noActions} role="status">
          No actions available
        </span>
      </div>
    )
  }

  // Sort: primary first, then secondary
  const sorted = [...allowedTransitions].sort((a, b) => {
    const aPrimary = metaFor(a).primary ? 0 : 1
    const bPrimary = metaFor(b).primary ? 0 : 1
    return aPrimary - bPrimary
  })

  return (
    <div className={styles.bar} data-testid="transition-action-bar">
      {errorMessage && (
        <p className={styles.errorMessage} role="alert" data-testid="action-error">
          {errorMessage}
        </p>
      )}

      <div className={styles.buttons}>
        {sorted.map((event) => {
          const { label, icon, primary } = metaFor(event)
          return (
            <button
              key={event}
              type="button"
              className={primary ? styles.primaryButton : styles.secondaryButton}
              onClick={() => handleTap(event)}
              disabled={inFlight}
              aria-label={label}
              aria-busy={inFlight}
              data-testid={`action-${event.toLowerCase()}`}
            >
              {inFlight ? (
                <span className={styles.spinner} aria-hidden="true" />
              ) : (
                <span className={styles.icon} aria-hidden="true">{icon}</span>
              )}
              <span>{label}</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
