/**
 * BreachReasonDialog — inline breach attribution dialog.
 *
 * Fetches the server-provided reason-code controlled vocabulary.
 * Submits with Idempotency-Key on every attempt so retries are safe.
 *
 * Error mapping:
 *   400 → field-level validation message
 *   403 → access message (no existence disclosure)
 *   409 → conflict prompt with refresh suggestion
 *   429 → retry-after message
 *   5xx → generic retry message
 *
 * @module features/sla/BreachReasonDialog
 */

import React, { useId, useState } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';

import { apiFetch } from '../../api/http.js';
import { queryClient } from '../../api/queryClient.js';

import styles from './BreachReasonDialog.module.css';

/**
 * @param {{
 *   open: boolean,
 *   workOrderId: string,
 *   workOrderRef: string,
 *   onClose: () => void,
 *   onSuccess?: () => void,
 * }} props
 */
export function BreachReasonDialog({ open, workOrderId, workOrderRef, onClose, onSuccess }) {
  const titleId = useId();

  const [selectedCode, setSelectedCode] = useState('');
  const [fieldError,    setFieldError]   = useState(/** @type {string | null} */ (null));
  const [conflictMsg,   setConflictMsg]  = useState(/** @type {string | null} */ (null));
  const [accessError,   setAccessError]  = useState(/** @type {string | null} */ (null));
  const [rateLimitMsg,  setRateLimitMsg] = useState(/** @type {string | null} */ (null));
  const [genericError,  setGenericError] = useState(/** @type {string | null} */ (null));

  function clearErrors() {
    setFieldError(null);
    setConflictMsg(null);
    setAccessError(null);
    setRateLimitMsg(null);
    setGenericError(null);
  }

  // Fetch controlled vocabulary — enabled only while dialog is open
  const { data: vocabData, isLoading: vocabLoading } = useQuery({
    queryKey: ['breach-reason-codes'],
    queryFn:  () => apiFetch('/sla/breach-reason-codes'),
    enabled:  open,
    staleTime: 5 * 60_000,
  });

  const reasonCodes = vocabData?.data ?? [];

  const { mutate, isPending } = useMutation({
    mutationFn: () =>
      apiFetch(`/work-orders/${workOrderId}/breach-reason`, {
        method:  'POST',
        body:    JSON.stringify({ reasonCode: selectedCode }),
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['work-order', workOrderId] });
      queryClient.invalidateQueries({ queryKey: ['sla-alerts'] });
      onSuccess?.();
      onClose();
    },
    onError: (err) => {
      clearErrors();
      const httpStatus = err?.status;

      if (httpStatus === 400) {
        const fe = err?.body?.fieldErrors ?? [];
        setFieldError(fe.length > 0 ? fe[0].message : 'Invalid request — please check your selection.');
      } else if (httpStatus === 403) {
        setAccessError('You do not have permission to attribute this breach.');
      } else if (httpStatus === 409) {
        setConflictMsg(
          'This breach has already been attributed. Refresh the page to see the latest state.',
        );
      } else if (httpStatus === 429) {
        const retryAfter = err?.body?.retryAfterSeconds ?? 60;
        setRateLimitMsg(`Too many requests. Please wait ${retryAfter} seconds before trying again.`);
      } else {
        setGenericError('An unexpected error occurred. Please try again.');
      }
    },
  });

  if (!open) return null;

  return (
    <div
      className={styles.overlay}
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div className={styles.dialog}>
        <header className={styles.header}>
          <h2 id={titleId} className={styles.title}>
            Attribute Breach — {workOrderRef}
          </h2>
          <button
            type="button"
            className={styles.closeBtn}
            onClick={onClose}
            aria-label="Close dialog"
          >
            ✕
          </button>
        </header>

        <div className={styles.body}>
          {/* Error banners */}
          {accessError && (
            <div className={styles.bannerError} role="alert">{accessError}</div>
          )}
          {conflictMsg && (
            <div className={styles.bannerWarning} role="alert">{conflictMsg}</div>
          )}
          {rateLimitMsg && (
            <div className={styles.bannerWarning} role="alert">{rateLimitMsg}</div>
          )}
          {genericError && (
            <div className={styles.bannerError} role="alert">{genericError}</div>
          )}

          {/* Content — hidden when no permission */}
          {!accessError && (
            <>
              {vocabLoading && (
                <div className={styles.loading} role="status">Loading reason codes…</div>
              )}

              {!vocabLoading && (
                <fieldset className={styles.fieldset}>
                  <legend className={styles.legend}>Select a reason code</legend>

                  {reasonCodes.map((rc) => (
                    <label key={rc.code} className={styles.option}>
                      <input
                        type="radio"
                        name="reasonCode"
                        value={rc.code}
                        checked={selectedCode === rc.code}
                        onChange={() => {
                          setSelectedCode(rc.code);
                          setFieldError(null);
                        }}
                      />
                      <span className={styles.optionContent}>
                        <span className={styles.optionLabel}>{rc.displayName}</span>
                        {rc.description && (
                          <span className={styles.optionDesc}>{rc.description}</span>
                        )}
                      </span>
                    </label>
                  ))}

                  {fieldError && (
                    <div className={styles.fieldError} role="alert">{fieldError}</div>
                  )}
                </fieldset>
              )}

              <div className={styles.actions}>
                <button
                  type="button"
                  className={styles.cancelBtn}
                  onClick={onClose}
                  disabled={isPending}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className={styles.submitBtn}
                  onClick={() => { clearErrors(); mutate(); }}
                  disabled={!selectedCode || isPending}
                  aria-disabled={!selectedCode || isPending}
                >
                  {isPending ? 'Submitting…' : 'Submit'}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
