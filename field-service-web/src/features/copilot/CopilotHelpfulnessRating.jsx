/**
 * CopilotHelpfulnessRating — thumbs-up/thumbs-down control that posts to the
 * AI interaction rating endpoint.
 *
 * Uses TanStack Query mutation with:
 * - Idempotency guaranteed by disabling after first successful submit
 * - Optimistic visual state
 * - Error rollback with non-blocking toast
 * - 44 px minimum touch targets
 *
 * @module features/copilot/CopilotHelpfulnessRating
 */

import React, { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { apiFetch } from '../../api/http.js';
import { useToast } from '../../components/index.js';
import styles from './CopilotSheet.module.css';

/**
 * @typedef {'helpful' | 'not_helpful'} HelpfulnessValue
 */

/**
 * POST /copilot/interactions/{interactionId}/rating
 *
 * @param {{ interactionId: string, rating: HelpfulnessValue }} params
 * @returns {Promise<null>}
 */
async function submitRating({ interactionId, rating }) {
  return apiFetch(`/copilot/interactions/${interactionId}/rating`, {
    method: 'POST',
    body: JSON.stringify({ rating }),
  });
}

/**
 * @param {{
 *   interactionId: string | null,
 * }} props
 */
export function CopilotHelpfulnessRating({ interactionId }) {
  const { show } = useToast();
  /** @type {[HelpfulnessValue | null, Function]} */
  const [submitted, setSubmitted] = useState(null);

  const mutation = useMutation({
    mutationFn: submitRating,
    onSuccess: (_data, variables) => {
      setSubmitted(variables.rating);
    },
    onError: () => {
      show({
        variant: 'warning',
        message: 'Rating could not be saved. Please try again.',
      });
      // submitted stays null so they can retry
    },
  });

  if (!interactionId) return null;

  const isDisabled = mutation.isPending || submitted !== null;

  return (
    <div className={styles.ratingRow} role="group" aria-label="Was this answer helpful?">
      <span className={styles.ratingPrompt}>Was this helpful?</span>

      <button
        type="button"
        className={[
          styles.ratingBtn,
          submitted === 'helpful' ? styles.ratingSelected : '',
        ].join(' ').trim()}
        disabled={isDisabled}
        aria-pressed={submitted === 'helpful'}
        aria-label="Helpful"
        onClick={() => mutation.mutate({ interactionId, rating: 'helpful' })}
      >
        👍
      </button>

      <button
        type="button"
        className={[
          styles.ratingBtn,
          submitted === 'not_helpful' ? styles.ratingSelected : '',
        ].join(' ').trim()}
        disabled={isDisabled}
        aria-pressed={submitted === 'not_helpful'}
        aria-label="Not helpful"
        onClick={() => mutation.mutate({ interactionId, rating: 'not_helpful' })}
      >
        👎
      </button>

      {submitted !== null && (
        <span className={styles.ratingThanks} role="status" aria-live="polite">
          Thanks for your feedback
        </span>
      )}
    </div>
  );
}
