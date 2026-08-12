/**
 * SurveyPage — satisfaction survey form for a closed service request (WO-175).
 *
 * AC coverage:
 * - AC-7  1–5 score radio group (ScoreRadioGroup), required.
 * - AC-8  Optional NPS 0–10 radio group.
 * - AC-9  Optional comment, max 1000 chars, with PII notice.
 * - AC-10 Already-answered view renders submitted score, not the form.
 * - AC-11 Expired-window view shows clear message.
 * - AC-12 409 (already answered server-side), 422/400 inline error handling.
 * - AC-13 WCAG 2.1 AA — radiogroup roles, aria-live announcements.
 */

import React, { useId, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import {
  PageHeader,
  LoadingState,
  ErrorState,
  StateSurface,
  FormField,
  Button,
  ScoreRadioGroup,
} from '../../components/index.js';

import { fetchSurveyState, submitSurvey } from '../../api/portalClient.js';

import styles from './SurveyPage.module.css';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function fmtDate(isoString) {
  if (!isoString) return '';
  return new Date(isoString).toLocaleDateString(undefined, {
    year: 'numeric', month: 'long', day: 'numeric',
  });
}

function isWindowExpired(windowExpiresAt) {
  if (!windowExpiresAt) return false;
  return new Date(windowExpiresAt) < new Date();
}

// Generate a stable idempotency key for the lifetime of this form instance.
function useIdempotencyKey() {
  const keyRef = useRef(null);
  if (!keyRef.current) {
    keyRef.current = `survey-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
  return keyRef.current;
}

// ─── Already-answered view ────────────────────────────────────────────────────

function AlreadyAnswered({ outcome, reference }) {
  return (
    <div className={styles.page}>
      <PageHeader title="Satisfaction survey" subtitle={reference} />
      <StateSurface className={styles.card}>
        <h2 className={styles.cardHeading}>Survey already submitted</h2>
        <p className={styles.cardBody}>
          You submitted your feedback on {fmtDate(outcome?.submittedAt)}.
          Thank you for helping us improve.
        </p>
        {outcome?.score != null && (
          <dl className={styles.outcomeDl}>
            <div className={styles.outcomeDlItem}>
              <dt className={styles.outcomeDt}>Score</dt>
              <dd className={styles.outcomeDd}>{outcome.score} / 5</dd>
            </div>
            {outcome.nps != null && (
              <div className={styles.outcomeDlItem}>
                <dt className={styles.outcomeDt}>Likelihood to recommend</dt>
                <dd className={styles.outcomeDd}>{outcome.nps} / 10</dd>
              </div>
            )}
          </dl>
        )}
        <Link to="/portal/history" className={styles.backLink}>
          ← Back to service history
        </Link>
      </StateSurface>
    </div>
  );
}

// ─── Expired-window view ──────────────────────────────────────────────────────

function ExpiredWindow({ reference }) {
  return (
    <div className={styles.page}>
      <PageHeader title="Satisfaction survey" subtitle={reference} />
      <StateSurface className={styles.card}>
        <h2 className={styles.cardHeading}>Survey window has closed</h2>
        <p className={styles.cardBody}>
          The feedback window for this service request has passed.
          We appreciate your business and look forward to serving you again.
        </p>
        <Link to="/portal/history" className={styles.backLink}>
          ← Back to service history
        </Link>
      </StateSurface>
    </div>
  );
}

// ─── Survey form ──────────────────────────────────────────────────────────────

const MAX_COMMENT_LENGTH = 1000;

function SurveyForm({ workOrderId, reference, onSuccess }) {
  const [score, setScore]     = useState(null);
  const [nps, setNps]         = useState(null);
  const [comment, setComment] = useState('');
  const [fieldError, setFieldError] = useState(null);
  const idempotencyKey = useIdempotencyKey();
  const statusRef      = useRef(null);
  const scoreId        = useId();
  const npsId          = useId();
  const commentId      = useId();
  const queryClient    = useQueryClient();

  const { mutate, isPending, isError, error } = useMutation({
    mutationFn: () => submitSurvey(
      workOrderId,
      {
        score,
        nps: nps ?? null,
        comment: comment.trim() || null,
      },
      idempotencyKey,
    ),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['portal', 'survey', workOrderId] });
      onSuccess(result);
    },
  });

  function handleSubmit(e) {
    e.preventDefault();
    if (score == null) {
      setFieldError('Please select a score before submitting.');
      return;
    }
    setFieldError(null);
    mutate();
  }

  // Server error label
  let serverErrorMsg = null;
  if (isError) {
    if (error?.status === 409) {
      serverErrorMsg = 'This survey has already been submitted.';
    } else if (error?.status === 422 || error?.status === 400) {
      serverErrorMsg = error?.body?.message ?? 'Your submission was invalid. Please check your responses and try again.';
    } else {
      serverErrorMsg = 'Something went wrong. Please try again.';
    }
  }

  return (
    <div className={styles.page}>
      <PageHeader
        title="Satisfaction survey"
        subtitle={`How did we do for ${reference}?`}
      />

      <StateSurface className={styles.card}>
        <form
          className={styles.form}
          onSubmit={handleSubmit}
          noValidate
          aria-label="Satisfaction survey form"
        >
          {/* Score — required */}
          <div className={styles.fieldGroup}>
            <ScoreRadioGroup
              id={scoreId}
              name="score"
              min={1}
              max={5}
              value={score}
              onChange={setScore}
              disabled={isPending}
              lowLabel="Very dissatisfied"
              highLabel="Very satisfied"
            />
            {fieldError && (
              <p
                className={styles.fieldError}
                id={`${scoreId}-error`}
                role="alert"
                aria-live="assertive"
              >
                {fieldError}
              </p>
            )}
          </div>

          {/* NPS — optional */}
          <div className={styles.fieldGroup}>
            <p className={styles.optionalLabel} id={npsId}>
              How likely are you to recommend us to a colleague or friend?
              <span className={styles.optionalHint}> (optional)</span>
            </p>
            <ScoreRadioGroup
              id={`${npsId}-input`}
              name="nps"
              min={0}
              max={10}
              value={nps}
              onChange={setNps}
              disabled={isPending}
              lowLabel="Not at all likely"
              highLabel="Extremely likely"
            />
          </div>

          {/* Comment — optional */}
          <div className={styles.fieldGroup}>
            <label htmlFor={commentId} className={styles.commentLabel}>
              Additional feedback
              <span className={styles.optionalHint}> (optional)</span>
            </label>
            <textarea
              id={commentId}
              className={styles.commentTextarea}
              value={comment}
              onChange={e => setComment(e.target.value)}
              maxLength={MAX_COMMENT_LENGTH}
              rows={4}
              disabled={isPending}
              aria-describedby={`${commentId}-hint ${commentId}-count`}
            />
            <p id={`${commentId}-hint`} className={styles.piiNotice}>
              Do not include personal information such as names, email addresses, or phone numbers.
            </p>
            <p id={`${commentId}-count`} className={styles.charCount} aria-live="polite">
              {comment.length}/{MAX_COMMENT_LENGTH} characters
            </p>
          </div>

          {/* Server error */}
          {serverErrorMsg && (
            <div
              className={styles.serverError}
              role="alert"
              aria-live="assertive"
              ref={statusRef}
            >
              {serverErrorMsg}
            </div>
          )}

          <div className={styles.formActions}>
            <Button
              type="submit"
              variant="primary"
              disabled={isPending}
              aria-busy={isPending}
            >
              {isPending ? 'Submitting…' : 'Submit feedback'}
            </Button>
            <Link to="/portal/history" className={styles.cancelLink}>
              Cancel
            </Link>
          </div>
        </form>
      </StateSurface>
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function SurveyPage() {
  const { requestId } = useParams();
  const [submitted, setSubmitted] = useState(null);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['portal', 'survey', requestId],
    queryFn: ({ signal }) => fetchSurveyState(requestId, { signal }),
    enabled: Boolean(requestId),
    staleTime: 60_000,
  });

  if (isLoading && !data) {
    return (
      <div className={styles.page}>
        <PageHeader title="Satisfaction survey" />
        <LoadingState label="Loading survey…" />
      </div>
    );
  }

  if (isError) {
    const status = error?.status;
    if (status === 404 || status === 403) {
      return (
        <div className={styles.page}>
          <PageHeader title="Satisfaction survey" />
          <ErrorState
            title="Survey not found"
            description="This survey link is no longer valid."
            actions={<Link to="/portal/history" className={styles.backLink}>Back to service history</Link>}
          />
        </div>
      );
    }
    return (
      <div className={styles.page}>
        <PageHeader title="Satisfaction survey" />
        <ErrorState
          title="Could not load survey"
          description={error?.message ?? 'Please try again later.'}
          actions={<Link to="/portal/history" className={styles.backLink}>Back to service history</Link>}
        />
      </div>
    );
  }

  if (!data) return null;

  const { workOrderId, reference, windowExpiresAt, alreadyAnswered, outcome } = data;

  // Post-submission success view
  if (submitted) {
    return (
      <div className={styles.page}>
        <PageHeader title="Satisfaction survey" subtitle={reference} />
        <StateSurface className={styles.card}>
          <h2 className={styles.cardHeading}>Thank you for your feedback!</h2>
          <p className={styles.cardBody}>
            Your response has been recorded. We use your feedback to continuously improve our service.
          </p>
          <Link to="/portal/history" className={styles.backLink}>
            ← Back to service history
          </Link>
        </StateSurface>
      </div>
    );
  }

  // Already answered
  if (alreadyAnswered) {
    return <AlreadyAnswered outcome={outcome} reference={reference} />;
  }

  // Window expired
  if (isWindowExpired(windowExpiresAt)) {
    return <ExpiredWindow reference={reference} />;
  }

  return (
    <SurveyForm
      workOrderId={workOrderId}
      reference={reference}
      onSuccess={setSubmitted}
    />
  );
}
