/**
 * @fileoverview CopilotSheet — bottom-anchored copilot sheet for the technician PWA (WO-179).
 *
 * Layout: bottom sheet with a fixed-height answer region (skeleton preserves space),
 * a permanently visible Advisory label while an answer is shown, and an expandable
 * basis disclosure below the answer.
 *
 * Security: model text is rendered as plain text nodes — never dangerouslySetInnerHTML.
 * stripControlChars() is applied by useCopilotStream before text reaches this component.
 *
 * Accessibility:
 * - role="dialog" with aria-modal="true" and aria-label.
 * - Focus trap: first focusable element receives focus on mount.
 * - Escape dismisses the sheet.
 * - role="status" / aria-live="polite" live region announces state changes.
 * - aria-expanded on the basis toggle is inside CopilotBasisList.
 * - All interactive targets are ≥44 × 44 px via the CSS.
 *
 * CLS: the answer region has a fixed min-height with a skeleton placeholder
 * so no layout shift occurs when the first token arrives.
 *
 * INP: text state is batched on animation frames inside useCopilotStream;
 * this component only re-renders when flushed state changes.
 *
 * Feature gate: the sheet is only rendered when the server-reported capability
 * flag `copilotEnabled` is true (checked by the parent via useCopilotCapability).
 */
import { useEffect, useRef } from 'react'
import { useCopilotStream } from './useCopilotStream.js'
import { CopilotBasisList } from './CopilotBasisList.jsx'
import { CopilotHelpfulnessRating } from './CopilotHelpfulnessRating.jsx'
import {
  CopilotState,
  ADVISORY_STATES,
  ANSWER_STATES,
  RETRYABLE_STATES,
  STATE_ANNOUNCEMENT,
} from './copilotStates.js'
import styles from './CopilotSheet.module.css'

const ADVISORY_COPY =
  'This guidance is advisory. Your professional judgement governs — verify all steps before acting.'

/**
 * @param {{
 *   workOrderId: string,
 *   onDismiss: () => void,
 * }} props
 */
export function CopilotSheet({ workOrderId, onDismiss }) {
  const { state, text, interactionId, basis, retryAfterSeconds, start, dismiss } =
    useCopilotStream(workOrderId)

  const closeButtonRef = useRef(/** @type {HTMLButtonElement|null} */ (null))

  // Focus the close button when the sheet mounts (focus trap entry)
  useEffect(() => {
    closeButtonRef.current?.focus()
  }, [])

  // Escape key dismisses the sheet
  useEffect(() => {
    function handleKey(e) {
      if (e.key === 'Escape') handleDismiss()
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  })

  function handleDismiss() {
    dismiss()
    onDismiss()
  }

  const announcement = STATE_ANNOUNCEMENT[state] ?? ''

  return (
    <div
      className={styles.overlay}
      role="presentation"
      data-testid="copilot-overlay"
      onClick={(e) => { if (e.target === e.currentTarget) handleDismiss() }}
    >
      <div
        className={styles.sheet}
        role="dialog"
        aria-modal="true"
        aria-label="Copilot — job assistance"
        data-testid="copilot-sheet"
        // Swipe-down gesture handled by CSS touch-action; dismiss via overlay click or button
      >
        {/* ── Header ──────────────────────────────────────────────────────── */}
        <div className={styles.header}>
          <div className={styles.dragHandle} aria-hidden="true" />
          <h2 className={styles.title}>Copilot</h2>
          <button
            ref={closeButtonRef}
            type="button"
            className={styles.closeButton}
            aria-label="Close copilot"
            onClick={handleDismiss}
            data-testid="copilot-close"
          >
            ✕
          </button>
        </div>

        {/* ── ARIA live region — state announcements ─────────────────────── */}
        <div
          role="status"
          aria-live="polite"
          aria-atomic="true"
          className={styles.srOnly}
          data-testid="copilot-live-region"
        >
          {announcement}
        </div>

        {/* ── Answer region (fixed height to prevent CLS) ─────────────────── */}
        <div className={styles.answerRegion} data-testid="copilot-answer-region">

          {/* Advisory label — visible whenever an answer is being shown */}
          {ADVISORY_STATES.has(state) && (
            <div
              className={styles.advisoryLabel}
              role="note"
              aria-label="Advisory notice"
              data-testid="advisory-label"
            >
              <span className={styles.advisoryIcon} aria-hidden="true">ⓘ</span>
              <span>{ADVISORY_COPY}</span>
            </div>
          )}

          {/* Skeleton while streaming has not yet produced text */}
          {state === CopilotState.STREAMING && text === '' && (
            <div className={styles.skeleton} aria-hidden="true">
              <div className={styles.skeletonLine} style={{ width: '92%' }} />
              <div className={styles.skeletonLine} style={{ width: '80%' }} />
              <div className={styles.skeletonLine} style={{ width: '70%' }} />
            </div>
          )}

          {/* Streaming / partial / complete — plain text rendering (AC-5) */}
          {(state === CopilotState.STREAMING || ANSWER_STATES.has(state)) && text !== '' && (
            <p
              className={styles.answerText}
              data-testid="copilot-answer-text"
              // Intentionally plain text — model output is untrusted
            >
              {text}
              {state === CopilotState.STREAMING && (
                <span className={styles.cursor} aria-hidden="true" />
              )}
            </p>
          )}

          {/* PARTIAL state notice */}
          {state === CopilotState.PARTIAL && (
            <p className={styles.partialNotice} role="note" data-testid="partial-notice">
              The answer ended before completing. The text above may be incomplete.
            </p>
          )}

          {/* REFUSED state (AC-6) */}
          {state === CopilotState.REFUSED && (
            <div className={styles.terminalState} data-testid="refused-state">
              <span className={styles.terminalIcon} aria-hidden="true">○</span>
              <p className={styles.terminalHeading}>No grounded basis available</p>
              <p className={styles.terminalBody}>
                There is no service history or asset record that can support a grounded
                answer for this job. Proceeding without copilot guidance is safe — consult
                the manual or your team lead if needed.
              </p>
            </div>
          )}

          {/* DEGRADED state (AC-7) */}
          {state === CopilotState.DEGRADED && (
            <div className={styles.terminalState} data-testid="degraded-state">
              <span className={styles.terminalIcon} aria-hidden="true">⚠</span>
              <p className={styles.terminalHeading}>Copilot is temporarily unavailable</p>
              <p className={styles.terminalBody}>
                The copilot service could not be reached. Your job details are unaffected —
                tap Retry to try again, or dismiss to continue working.
              </p>
            </div>
          )}

          {/* CAPPED state (AC-7) */}
          {state === CopilotState.CAPPED && (
            <div className={styles.terminalState} data-testid="capped-state">
              <span className={styles.terminalIcon} aria-hidden="true">⊗</span>
              <p className={styles.terminalHeading}>Daily interaction limit reached</p>
              <p className={styles.terminalBody}>
                {retryAfterSeconds
                  ? `Copilot interactions will resume in approximately ${Math.ceil(retryAfterSeconds / 60)} minute${retryAfterSeconds >= 120 ? 's' : ''}.`
                  : 'Your daily copilot interaction allowance has been used. Try again tomorrow or contact your supervisor.'}
              </p>
            </div>
          )}

          {/* IDLE state — entry prompt */}
          {state === CopilotState.IDLE && (
            <p className={styles.idlePrompt} data-testid="idle-prompt">
              Ask copilot for guidance on this job.
            </p>
          )}
        </div>

        {/* ── Basis section (collapsible, AC-4) ─────────────────────────── */}
        {state === CopilotState.COMPLETE && (
          <CopilotBasisList basis={basis} />
        )}

        {/* ── Helpfulness rating (AC-8) ─────────────────────────────────── */}
        {state === CopilotState.COMPLETE && interactionId && (
          <CopilotHelpfulnessRating interactionId={interactionId} />
        )}

        {/* ── Action bar ────────────────────────────────────────────────── */}
        <div className={styles.actionBar}>
          {/* Primary action: Ask / Retry */}
          {RETRYABLE_STATES.has(state) ? (
            <button
              type="button"
              className={styles.primaryButton}
              onClick={start}
              data-testid="copilot-retry"
            >
              Retry
            </button>
          ) : state === CopilotState.IDLE ? (
            <button
              type="button"
              className={styles.primaryButton}
              onClick={start}
              data-testid="copilot-ask"
            >
              Ask copilot
            </button>
          ) : null}

          {/* Always-present dismiss / close */}
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={handleDismiss}
            data-testid="copilot-dismiss"
          >
            {state === CopilotState.COMPLETE ? 'Close' : 'Dismiss'}
          </button>
        </div>
      </div>
    </div>
  )
}
