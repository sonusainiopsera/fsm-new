/**
 * @fileoverview SurveyPage — CSAT satisfaction survey form.
 *
 * Implements AC-6 through AC-8 from WO-175:
 *   - Accessible ScoreRadioGroup for 1-5 score (not an unlabelled icon row).
 *   - Optional 0-10 NPS radio control with visible labels.
 *   - Optional comment field (max 1000 chars) with live counter and PII notice.
 *   - Keyboard-only form submission supported.
 *   - Server is the authority on duplicates (409) and window expiry (422).
 *   - 409 → read-only already-answered state with plain-language wording.
 *   - 422 → read-only expired-window state with plain-language wording.
 *   - 400 → inline field errors.
 *   - Comment is never echoed in error payloads or analytics.
 *   - No internal enum codes in user-facing text.
 */

import { useState, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import {
  PageHeader, EmptyState, LoadingState, ErrorState, Button,
} from '../../components/index.js'
import { ScoreRadioGroup } from '../../components/survey/ScoreRadioGroup.jsx'
import { useSurveys, useSubmitSurveyResponse } from '../../api/portalClient.js'

const COMMENT_MAX = 1000

const NPS_OPTIONS = Array.from({ length: 11 }, (_, i) => i)
const NPS_LABELS = {
  0: 'Not at all likely',
  5: 'Neutral',
  10: 'Extremely likely',
}

/**
 * Formats a submitted-at timestamp for display.
 * @param {string} isoDate
 * @returns {string}
 */
function formatDate(isoDate) {
  try {
    return new Date(isoDate).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' })
  } catch {
    return isoDate
  }
}

export default function SurveyPage() {
  const { workOrderId } = useParams()

  const [score, setScore] = useState(/** @type {number|null} */ (null))
  const [npsScore, setNpsScore] = useState(/** @type {number|null} */ (null))
  const [comment, setComment] = useState('')
  const [scoreError, setScoreError] = useState(/** @type {string|null} */ (null))
  const [submitted, setSubmitted] = useState(false)

  const { data: surveysEnvelope, isLoading, isError } = useSurveys(workOrderId)

  // Extract the first survey for this work order from the list
  const survey = surveysEnvelope?.data?.[0] ?? null

  const mutation = useSubmitSurveyResponse(survey?.id ?? '')

  // Derive refusal state from mutation error
  const alreadyAnswered = mutation.error?.status === 409
  const windowExpired = mutation.error?.status === 422
  const fieldErrors = mutation.error?.fieldErrors ?? []

  const validate = useCallback(() => {
    if (!score) {
      setScoreError('Please select a satisfaction score before submitting.')
      return false
    }
    setScoreError(null)
    return true
  }, [score])

  const handleSubmit = useCallback(async (e) => {
    e.preventDefault()
    if (!validate()) return

    try {
      await mutation.mutateAsync({
        score,
        npsScore: npsScore != null ? npsScore : null,
        // Comment not included in error payloads — see constraints
        comment: comment.trim() !== '' ? comment.trim() : null,
      })
      setSubmitted(true)
    } catch {
      // Error rendered from mutation.error below
    }
  }, [score, npsScore, comment, mutation, validate])

  if (isLoading) return <LoadingState />

  if (isError) {
    return <ErrorState message="We could not load your feedback form. Please refresh to try again." />
  }

  if (!survey) {
    return (
      <main style={{ maxWidth: 640, margin: '0 auto', padding: 'var(--token-space-6) var(--token-space-4)' }}>
        <PageHeader title="Share your feedback" />
        <EmptyState message="No feedback form is available for this service request." />
        <BackLink />
      </main>
    )
  }

  // Success state
  if (submitted) {
    return (
      <main style={{ maxWidth: 640, margin: '0 auto', padding: 'var(--token-space-6) var(--token-space-4)', fontFamily: 'var(--token-family-base)' }}>
        <PageHeader title="Thank you for your feedback" />
        <p style={{ fontSize: 'var(--token-fs-16)', color: 'var(--token-text-primary)', lineHeight: 1.6 }}>
          Your feedback has been recorded. We appreciate you taking the time to let us know how we did.
        </p>
        <BackLink />
      </main>
    )
  }

  // 409 — already answered
  if (alreadyAnswered) {
    const outcome = mutation.error?.outcome
    return (
      <main style={{ maxWidth: 640, margin: '0 auto', padding: 'var(--token-space-6) var(--token-space-4)', fontFamily: 'var(--token-family-base)' }}>
        <PageHeader title="Feedback already submitted" />
        <div
          role="status"
          style={{
            padding: 'var(--token-space-5)',
            background: 'var(--token-info-subtle)',
            border: '1px solid var(--token-info-default)',
            borderRadius: 'var(--token-radius-sm)',
            fontSize: 'var(--token-fs-15)',
            color: 'var(--token-text-primary)',
            lineHeight: 1.6,
          }}
        >
          <p style={{ margin: 0 }}>
            You have already submitted feedback for this service request.
          </p>
          {outcome?.submittedAt && (
            <p style={{ margin: 'var(--token-space-2) 0 0' }}>
              Submitted on {formatDate(outcome.submittedAt)}.
            </p>
          )}
        </div>
        <BackLink />
      </main>
    )
  }

  // 422 — window expired
  if (windowExpired) {
    return (
      <main style={{ maxWidth: 640, margin: '0 auto', padding: 'var(--token-space-6) var(--token-space-4)', fontFamily: 'var(--token-family-base)' }}>
        <PageHeader title="Feedback window closed" />
        <div
          role="status"
          style={{
            padding: 'var(--token-space-5)',
            background: 'var(--token-warning-subtle)',
            border: '1px solid var(--token-warning-default)',
            borderRadius: 'var(--token-radius-sm)',
            fontSize: 'var(--token-fs-15)',
            color: 'var(--token-text-primary)',
            lineHeight: 1.6,
          }}
        >
          The feedback window for this service request has closed. Feedback must be submitted within
          30 days of the service being completed.
        </div>
        <BackLink />
      </main>
    )
  }

  const commentLen = comment.length
  const commentTooLong = commentLen > COMMENT_MAX
  const isSubmitting = mutation.isPending

  return (
    <main
      style={{
        maxWidth: 640,
        margin: '0 auto',
        padding: 'var(--token-space-6) var(--token-space-4)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      <PageHeader title="Share your feedback" />

      <p style={{ fontSize: 'var(--token-fs-15)', color: 'var(--token-text-secondary)', marginBottom: 'var(--token-space-6)', lineHeight: 1.6 }}>
        Tell us how we did. Your feedback helps us improve the service we provide.
      </p>

      <form onSubmit={handleSubmit} noValidate>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-7)' }}>

          {/* 1-to-5 score (required) */}
          <ScoreRadioGroup
            name="score"
            value={score}
            onChange={(v) => { setScore(v); setScoreError(null) }}
            disabled={isSubmitting}
            error={scoreError ?? (fieldErrors.find(fe => fe.field === 'score')?.message ?? null)}
          />

          {/* NPS: 0-10 (optional) */}
          <fieldset style={{ border: 'none', padding: 0, margin: 0 }}>
            <legend
              style={{
                fontSize: 'var(--token-fs-15)',
                fontWeight: 600,
                color: 'var(--token-text-primary)',
                marginBottom: 'var(--token-space-3)',
                padding: 0,
              }}
            >
              How likely are you to recommend us to a colleague or business partner?
              <span
                style={{ fontSize: 'var(--token-fs-13)', fontWeight: 400, color: 'var(--token-text-secondary)', marginLeft: 8 }}
              >
                (optional)
              </span>
            </legend>

            <div
              role="group"
              aria-label="Likelihood to recommend, 0 to 10"
              style={{
                display: 'flex',
                flexWrap: 'wrap',
                gap: 'var(--token-space-2)',
              }}
            >
              {NPS_OPTIONS.map((val) => (
                <label
                  key={val}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 4,
                    minWidth: '44px',
                    minHeight: '44px',
                    padding: 'var(--token-space-1)',
                    cursor: isSubmitting ? 'default' : 'pointer',
                    color: isSubmitting ? 'var(--token-text-disabled)' : 'var(--token-text-primary)',
                    fontSize: 'var(--token-fs-13)',
                  }}
                >
                  <input
                    type="radio"
                    name="npsScore"
                    value={String(val)}
                    checked={npsScore === val}
                    onChange={() => setNpsScore(val)}
                    disabled={isSubmitting}
                    aria-label={`${val}${NPS_LABELS[val] ? ` — ${NPS_LABELS[val]}` : ''}`}
                    style={{ width: 20, height: 20, cursor: isSubmitting ? 'default' : 'pointer' }}
                  />
                  {val}
                  {NPS_LABELS[val] && (
                    <span style={{ fontSize: 'var(--token-fs-11)', color: 'var(--token-text-secondary)', textAlign: 'center' }}>
                      {NPS_LABELS[val]}
                    </span>
                  )}
                </label>
              ))}
            </div>
          </fieldset>

          {/* Comment (optional) with PII notice */}
          <div>
            <label
              htmlFor="survey-comment"
              style={{
                display: 'block',
                fontSize: 'var(--token-fs-15)',
                fontWeight: 600,
                color: 'var(--token-text-primary)',
                marginBottom: 'var(--token-space-2)',
              }}
            >
              Additional comments
              <span
                style={{ fontSize: 'var(--token-fs-13)', fontWeight: 400, color: 'var(--token-text-secondary)', marginLeft: 8 }}
              >
                (optional)
              </span>
            </label>

            <div
              style={{
                fontSize: 'var(--token-fs-13)',
                color: 'var(--token-text-secondary)',
                marginBottom: 'var(--token-space-2)',
                padding: 'var(--token-space-2) var(--token-space-3)',
                background: 'var(--token-info-subtle)',
                border: '1px solid var(--token-info-subtle)',
                borderRadius: 'var(--token-radius-sm)',
                lineHeight: 1.5,
              }}
              role="note"
            >
              <strong>Personal data notice:</strong> Any comments you enter here are stored as personal
              data and handled in accordance with our privacy policy.
            </div>

            <textarea
              id="survey-comment"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              rows={5}
              maxLength={COMMENT_MAX + 1}
              disabled={isSubmitting}
              placeholder="Tell us more about your experience…"
              aria-describedby="survey-comment-counter"
              style={{
                width: '100%',
                padding: 'var(--token-space-3) var(--token-space-4)',
                fontSize: 'var(--token-fs-15)',
                lineHeight: 1.5,
                border: `1px solid ${commentTooLong ? 'var(--token-danger-default)' : 'var(--token-border-default)'}`,
                borderRadius: 'var(--token-radius-sm)',
                background: 'var(--token-surface-0)',
                color: 'var(--token-text-primary)',
                resize: 'vertical',
                boxSizing: 'border-box',
              }}
            />
            <div
              id="survey-comment-counter"
              aria-live="polite"
              aria-atomic="true"
              style={{
                textAlign: 'right',
                fontSize: 'var(--token-fs-13)',
                color: commentTooLong ? 'var(--token-danger-emphasis)' : 'var(--token-text-secondary)',
                marginTop: 'var(--token-space-1)',
              }}
            >
              <span className="sr-only">Characters used: </span>
              {commentLen} / {COMMENT_MAX}
            </div>
          </div>

          {/* General mutation error */}
          {mutation.isError && !alreadyAnswered && !windowExpired && (
            <div
              role="alert"
              aria-live="assertive"
              style={{
                padding: 'var(--token-space-4)',
                background: 'var(--token-danger-subtle)',
                border: '1px solid var(--token-danger-default)',
                borderRadius: 'var(--token-radius-sm)',
                fontSize: 'var(--token-fs-15)',
                color: 'var(--token-text-primary)',
              }}
            >
              {mutation.error?.message ?? 'Something went wrong. Please try again.'}
            </div>
          )}

          <Button
            type="submit"
            variant="primary"
            disabled={isSubmitting || commentTooLong}
            aria-busy={isSubmitting}
            style={{ minHeight: '48px', fontSize: 'var(--token-fs-16)' }}
          >
            {isSubmitting ? 'Submitting…' : 'Submit feedback'}
          </Button>
        </div>
      </form>

      <BackLink />
    </main>
  )
}

function BackLink() {
  return (
    <div style={{ marginTop: 'var(--token-space-6)', borderTop: '1px solid var(--token-border-subtle)', paddingTop: 'var(--token-space-4)' }}>
      <Link
        to="/portal/history"
        style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-link)', textDecoration: 'underline' }}
      >
        ← Back to service history
      </Link>
    </div>
  )
}
