/**
 * TransitionActionBar — server-driven action bar for technician job lifecycle.
 *
 * Renders exactly the events in allowedTransitions from the server.
 * No transition button is hardcoded — the bar is purely server-driven.
 *
 * Idempotency: a stable key is generated per user tap intent (newAttemptKey())
 * and passed through to the mutation. The key is reset only when a new tap starts.
 *
 * HOLD opens HoldReasonSheet. All other events post immediately.
 *
 * @module app/technician/components/TransitionActionBar
 */

import React, { useState, useCallback } from 'react';
import { useWorkOrderTransition } from '../../../features/workorders/api/useWorkOrderTransition.js';
import { newAttemptKey } from '../../../lib/idempotency.js';
import { HoldReasonSheet } from './HoldReasonSheet.jsx';
import { mapApiError } from '../../../shared/api/errorMapping.js';
import styles from './TransitionActionBar.module.css';

const EVENT_LABELS = {
  DEPART:   'Depart',
  START:    'Start job',
  HOLD:     'Place on hold',
  RESUME:   'Resume',
  COMPLETE: 'Complete',
};

const EVENT_VARIANT = {
  DEPART:   'primary',
  START:    'primary',
  HOLD:     'warning',
  RESUME:   'primary',
  COMPLETE: 'success',
};

/**
 * @param {{
 *   workOrderId: string,
 *   expectedVersion: number,
 *   allowedTransitions: string[],
 *   holdReasons: Array<{code: string, label: string}>,
 *   onSuccess?: () => void,
 * }} props
 */
export function TransitionActionBar({
  workOrderId,
  expectedVersion,
  allowedTransitions,
  holdReasons,
  onSuccess,
}) {
  const [pendingEvent, setPendingEvent]       = useState(null);
  const [holdSheetOpen, setHoldSheetOpen]     = useState(false);
  const [attemptKey, setAttemptKey]           = useState(() => newAttemptKey());
  const [refusalMessage, setRefusalMessage]   = useState(null);

  const { transition, isPending } = useWorkOrderTransition({
    onSuccess: (data, vars) => {
      setPendingEvent(null);
      setAttemptKey(newAttemptKey());
      setRefusalMessage(null);
      onSuccess?.();
    },
    onRefusal: (refusal) => {
      const mapped = mapApiError(refusal?.originalError ?? refusal);
      setRefusalMessage(mapped?.message ?? refusal?.message ?? 'Action failed.');
      setPendingEvent(null);
    },
  });

  const fire = useCallback((event, holdReasonCode = null, note = null) => {
    setPendingEvent(event);
    setRefusalMessage(null);
    transition({
      workOrderId,
      event,
      expectedVersion,
      holdReasonCode,
      note,
      idempotencyKey: attemptKey,
    });
  }, [workOrderId, expectedVersion, attemptKey, transition]);

  const handleTap = useCallback((event) => {
    if (isPending) return;
    if (event === 'HOLD') {
      if (holdReasons.length === 0) return;
      setHoldSheetOpen(true);
      return;
    }
    fire(event);
  }, [isPending, holdReasons, fire]);

  const handleHoldConfirm = useCallback((reasonCode, note) => {
    setHoldSheetOpen(false);
    fire('HOLD', reasonCode, note || null);
  }, [fire]);

  if (!allowedTransitions || allowedTransitions.length === 0) {
    return (
      <div className={styles.bar} role="status">
        <p className={styles.readOnly}>No actions available for this job.</p>
      </div>
    );
  }

  return (
    <>
      {refusalMessage && (
        <div className={styles.refusal} role="alert" aria-live="assertive">
          {refusalMessage}
        </div>
      )}
      <div className={styles.bar} role="group" aria-label="Job actions">
        {allowedTransitions.map((event) => {
          const label   = EVENT_LABELS[event] ?? event;
          const variant = EVENT_VARIANT[event] ?? 'primary';
          const isThis  = pendingEvent === event;
          const isHoldDisabled = event === 'HOLD' && holdReasons.length === 0;

          return (
            <button
              key={event}
              type="button"
              className={`${styles.btn} ${styles[variant]}`}
              onClick={() => handleTap(event)}
              disabled={isPending || isHoldDisabled}
              aria-busy={isThis}
              aria-label={isHoldDisabled ? `${label} (reason list unavailable)` : label}
            >
              {isThis ? <span className={styles.spinner} aria-hidden="true" /> : null}
              {label}
            </button>
          );
        })}
      </div>

      <HoldReasonSheet
        open={holdSheetOpen}
        holdReasons={holdReasons}
        onConfirm={handleHoldConfirm}
        onCancel={() => setHoldSheetOpen(false)}
        isPending={isPending && pendingEvent === 'HOLD'}
      />
    </>
  );
}
