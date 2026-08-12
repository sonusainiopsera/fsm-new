/**
 * @fileoverview CopilotHelpfulnessRating — thumbs-up / thumbs-down rating control (WO-179 AC-8).
 *
 * Rules:
 * - Appears only when state === COMPLETE and interactionId is set.
 * - Uses useMutation (TanStack Query) keyed on interactionId.
 * - Optimistic acknowledgement: button reflects the selected rating immediately.
 * - Rollback on error: reverts to unrated state and surfaces a non-blocking toast.
 * - Duplicate-submit prevention: controls are disabled after any successful submission.
 * - The endpoint is POST /api/v1/ai-interactions/{interactionId}/rating with { helpful: boolean }.
 */
import { useState, useCallback } from 'react'
import { useMutation } from '@tanstack/react-query'
import { post } from '../../api/http.js'
import { useToast } from '../../components/Toast/ToastProvider.jsx'
import styles from './CopilotSheet.module.css'

/**
 * @param {{
 *   interactionId: string | null,
 * }} props
 */
export function CopilotHelpfulnessRating({ interactionId }) {
  const { toast } = useToast()
  /** @type {['helpful' | 'not_helpful' | null, Function]} */
  const [submitted, setSubmitted] = useState(null)

  const mutation = useMutation({
    mutationFn: (/** @type {boolean} */ helpful) =>
      post(`/ai-interactions/${interactionId}/rating`, { helpful }),
    onMutate: (helpful) => {
      setSubmitted(helpful ? 'helpful' : 'not_helpful')
    },
    onError: () => {
      setSubmitted(null)
      toast('Could not save your rating. Please try again.', 'warning')
    },
  })

  const rate = useCallback((helpful) => {
    if (!interactionId || submitted !== null || mutation.isPending) return
    mutation.mutate(helpful)
  }, [interactionId, submitted, mutation])

  if (!interactionId) return null

  const isDisabled = submitted !== null || mutation.isPending

  return (
    <div
      className={styles.ratingRow}
      role="group"
      aria-label="Was this answer helpful?"
      data-testid="helpfulness-rating"
    >
      <span className={styles.ratingLabel}>Was this helpful?</span>

      <button
        type="button"
        className={styles.ratingButton}
        aria-label="Helpful"
        aria-pressed={submitted === 'helpful'}
        disabled={isDisabled}
        onClick={() => rate(true)}
        data-testid="rate-helpful"
        data-selected={submitted === 'helpful' ? 'true' : undefined}
      >
        👍
      </button>

      <button
        type="button"
        className={styles.ratingButton}
        aria-label="Not helpful"
        aria-pressed={submitted === 'not_helpful'}
        disabled={isDisabled}
        onClick={() => rate(false)}
        data-testid="rate-not-helpful"
        data-selected={submitted === 'not_helpful' ? 'true' : undefined}
      >
        👎
      </button>

      {submitted !== null && (
        <span className={styles.ratingAck} role="status" aria-live="polite">
          {submitted === 'helpful' ? 'Marked helpful' : 'Marked not helpful'}
        </span>
      )}
    </div>
  )
}
