/**
 * CopilotSheet — bottom-anchored sheet with streamed AI answer, Advisory label,
 * expandable grounding basis, and helpfulness rating.
 *
 * Accessibility:
 *   - role="dialog", aria-modal="true", aria-labelledby
 *   - Focus trapped within sheet on open (first focusable element receives focus)
 *   - Escape key dismisses
 *   - role="status" aria-live="polite" live region for streaming updates
 *   - aria-expanded on basis disclosure (delegated to CopilotBasisList)
 *   - 44 px minimum touch targets on all interactive elements
 *
 * CLS: answerRegion has a reserved min-height of 120px + skeleton so tokens
 * appearing do not shift layout below the answer area.
 *
 * INP: token events are batched on requestAnimationFrame in useCopilotStream.
 *
 * @module features/copilot/CopilotSheet
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useCopilotStream } from './useCopilotStream.js';
import { CopilotBasisList } from './CopilotBasisList.jsx';
import { CopilotHelpfulnessRating } from './CopilotHelpfulnessRating.jsx';
import {
  STREAMING, COMPLETE, REFUSED, DEGRADED, CAPPED, PARTIAL,
  RATEABLE_STATES,
} from './copilotStates.js';
import styles from './CopilotSheet.module.css';

const ADVISORY_TEXT =
  'Advisory: This guidance is generated from historical records. ' +
  'Your professional judgement governs all decisions on site.';

/**
 * Inner sheet content — must live within a QueryClientProvider.
 *
 * @param {{
 *   workOrderId: string,
 *   onClose: () => void,
 * }} props
 */
function CopilotSheetInner({ workOrderId, onClose }) {
  const { state, answer, basis, interactionId, retryAfter, start, reset } =
    useCopilotStream();

  const [question, setQuestion] = useState('');
  const sheetRef       = useRef(null);
  const questionRef    = useRef(null);

  // Trap focus inside the sheet
  const handleKeyDown = useCallback(
    (e) => {
      if (e.key === 'Escape') {
        reset();
        onClose();
        return;
      }

      if (e.key === 'Tab') {
        const focusable = sheetRef.current?.querySelectorAll(
          'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ) ?? [];
        const first = focusable[0];
        const last  = focusable[focusable.length - 1];

        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last?.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }
    },
    [reset, onClose],
  );

  // Move focus into the sheet on mount
  useEffect(() => {
    questionRef.current?.focus();
  }, []);

  function handleAsk() {
    if (!question.trim() || state === STREAMING) return;
    start(workOrderId, question.trim());
  }

  function handleRetry() {
    if (question.trim()) {
      start(workOrderId, question.trim());
    }
  }

  const showAnswer    = state === COMPLETE || state === PARTIAL;
  const showStreaming = state === STREAMING;
  const showAdvisory  = showAnswer || showStreaming;
  const isRateable    = RATEABLE_STATES.has(state);

  return (
    <div
      className={styles.overlay}
      role="dialog"
      aria-modal="true"
      aria-labelledby="copilot-sheet-title"
    >
      <div
        ref={sheetRef}
        className={styles.sheet}
        onKeyDown={handleKeyDown}
      >
        {/* ── Header ── */}
        <div className={styles.sheetHeader}>
          <h2 id="copilot-sheet-title" className={styles.sheetTitle}>
            Copilot
          </h2>
          <button
            type="button"
            className={styles.closeBtn}
            onClick={() => { reset(); onClose(); }}
            aria-label="Close copilot"
          >
            ✕
          </button>
        </div>

        {/* ── Scrollable body ── */}
        <div className={styles.sheetBody}>
          {/* Advisory label — sticky, always visible when answer is present */}
          {showAdvisory && (
            <div className={styles.advisoryBanner} role="note" aria-label="Advisory notice">
              <p className={styles.advisoryText}>{ADVISORY_TEXT}</p>
            </div>
          )}

          {/* Answer / state region */}
          <div className={styles.answerRegion}>
            {/* Live region for screen reader announcements */}
            <div
              role="status"
              aria-live="polite"
              aria-atomic="false"
              aria-label="Copilot answer"
            >
              {/* Streaming skeleton — preserves space while tokens arrive */}
              {showStreaming && !answer && (
                <div className={styles.skeleton} aria-hidden="true">
                  <div className={styles.skeletonLine} />
                  <div className={styles.skeletonLine} />
                  <div className={styles.skeletonLine} />
                  <div className={styles.skeletonLine} />
                </div>
              )}

              {/* Rendered answer (plain text — never dangerouslySetInnerHTML) */}
              {(showStreaming || showAnswer) && answer && (
                <p className={styles.answerText}>
                  {answer}
                  {state === PARTIAL && (
                    <>
                      {' '}
                      <span className={styles.partialBadge}>Incomplete</span>
                    </>
                  )}
                </p>
              )}
            </div>

            {/* Terminal states */}
            {state === REFUSED && (
              <div className={`${styles.statePanel} ${styles['statePanel--refused']}`} role="alert">
                <p className={styles.statePanelTitle}>No grounded basis available</p>
                <p className={styles.statePanelMessage}>
                  There is not enough verified historical information for this asset to generate a
                  grounded answer. Your professional expertise and the on-site manual should guide
                  next steps.
                </p>
              </div>
            )}

            {state === DEGRADED && (
              <div className={`${styles.statePanel} ${styles['statePanel--degraded']}`} role="alert">
                <p className={styles.statePanelTitle}>Copilot unavailable</p>
                <p className={styles.statePanelMessage}>
                  The copilot service could not be reached. Your workspace is unaffected — you can
                  continue working normally. Tap retry when you have connectivity.
                </p>
                <button type="button" className={styles.retryBtn} onClick={handleRetry}>
                  Retry
                </button>
              </div>
            )}

            {state === CAPPED && (
              <div className={`${styles.statePanel} ${styles['statePanel--capped']}`} role="alert">
                <p className={styles.statePanelTitle}>Daily limit reached</p>
                <p className={styles.statePanelMessage}>
                  {retryAfter != null && retryAfter > 0
                    ? `Copilot queries are limited. Please wait ${Math.ceil(retryAfter / 60)} minute${Math.ceil(retryAfter / 60) !== 1 ? 's' : ''} before trying again.`
                    : 'Copilot queries are limited for today. Please try again later.'}
                </p>
              </div>
            )}

            {state === PARTIAL && !answer && (
              <div className={`${styles.statePanel} ${styles['statePanel--partial']}`} role="alert">
                <p className={styles.statePanelTitle}>Answer incomplete</p>
                <p className={styles.statePanelMessage}>
                  The stream ended before a complete answer was received. The partial content above
                  may be incomplete.
                </p>
                <button type="button" className={styles.retryBtn} onClick={handleRetry}>
                  Retry
                </button>
              </div>
            )}
          </div>

          {/* Basis disclosure */}
          {(showAnswer || showStreaming) && basis && (
            <CopilotBasisList basis={basis} />
          )}

          {/* Helpfulness rating */}
          {isRateable && (
            <CopilotHelpfulnessRating interactionId={interactionId} />
          )}

          {/* Question input */}
          <div className={styles.questionRow}>
            <textarea
              ref={questionRef}
              className={styles.questionInput}
              rows={2}
              placeholder="Ask about this job, asset, or equipment…"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleAsk();
                }
              }}
              disabled={state === STREAMING}
              aria-label="Question for copilot"
            />
            <button
              type="button"
              className={styles.askBtn}
              onClick={handleAsk}
              disabled={!question.trim() || state === STREAMING}
              aria-busy={state === STREAMING}
            >
              {state === STREAMING ? '…' : 'Ask'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * @param {{
 *   open: boolean,
 *   workOrderId: string,
 *   onClose: () => void,
 * }} props
 */
export function CopilotSheet({ open, workOrderId, onClose }) {
  if (!open) return null;
  return <CopilotSheetInner workOrderId={workOrderId} onClose={onClose} />;
}
