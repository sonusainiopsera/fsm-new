import React, { useState, useCallback, useEffect, useId } from 'react';
import { Modal } from '../../../components/Modal/Modal.jsx';
import { Button } from '../../../components/Button/Button.jsx';
import { useToast } from '../../../components/Toast/ToastProvider.jsx';
import { useAssignTechnician } from '../api/useAssignTechnician.js';
import { OverrideReasonField } from './OverrideReasonField.jsx';
import { ReassignmentReasonSelect } from './ReassignmentReasonSelect.jsx';
import { AppointmentImpactAcknowledgement } from './AppointmentImpactAcknowledgement.jsx';
import { PartsWarningNotice } from './PartsWarningNotice.jsx';
import styles from './AssignmentDialog.module.css';

/**
 * @typedef {'form' | 'cert_refused' | 'conflict' | 'retry_after' | 'error'} DialogStep
 */

/**
 * Assignment/reassignment confirmation dialog.
 *
 * - mode=assign: shows technician summary, optional override reason, parts warning
 * - mode=reassign: additionally requires reassignment reason and notes
 * - On 422 CONFIRMED_APPOINTMENT_BREACH: reveals appointment ack step within the form
 * - On 422 cert guard: switches to terminal refusal screen
 * - On 409: switches to conflict+refresh screen
 * - Idempotency-Key is stable per submission sequence
 *
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   workOrderId: string,
 *   mode?: 'assign' | 'reassign',
 *   candidate: {
 *     technicianId: string,
 *     technicianName: string,
 *     rank?: number,
 *     score?: number,
 *     factors?: unknown[],
 *   } | null,
 *   snapshotId?: string | null,
 *   partsWarnings?: unknown[],
 *   onRefreshRecommendations?: () => void,
 * }} props
 */
export function AssignmentDialog({
  open,
  onClose,
  workOrderId,
  mode = 'assign',
  candidate,
  snapshotId,
  partsWarnings = [],
  onRefreshRecommendations,
}) {
  const toast = useToast();
  const warnId = useId();

  // ── Form state ────────────────────────────────────────────────────────────

  const [overrideReason, setOverrideReason] = useState('');
  const [reassignmentReason, setReassignmentReason] = useState('');
  const [reassignmentNotes, setReassignmentNotes] = useState('');
  const [appointmentAck, setAppointmentAck] = useState('');

  /** @type {[DialogStep, React.Dispatch<React.SetStateAction<DialogStep>>]} */
  const [step, setStep] = useState('form');
  const [appointmentBreachShown, setAppointmentBreachShown] = useState(false);
  const [overrideErrors, setOverrideErrors] = useState(/** @type {string[]} */ ([]));
  const [reasonErrors, setReasonErrors] = useState(/** @type {string[]} */ ([]));
  const [ackErrors, setAckErrors] = useState(/** @type {string[]} */ ([]));
  const [serverErrorMsg, setServerErrorMsg] = useState(/** @type {string | null} */ (null));
  const [retryAfterSecs, setRetryAfterSecs] = useState(/** @type {number | null} */ (null));
  const [guardDetail, setGuardDetail] = useState(/** @type {string | null} */ (null));

  const { mutate, isPending, refreshIdempotencyKey } = useAssignTechnician({ workOrderId, mode });

  // ── Reset when dialog opens ───────────────────────────────────────────────

  useEffect(() => {
    if (open) {
      setOverrideReason('');
      setReassignmentReason('');
      setReassignmentNotes('');
      setAppointmentAck('');
      setStep('form');
      setAppointmentBreachShown(false);
      setOverrideErrors([]);
      setReasonErrors([]);
      setAckErrors([]);
      setServerErrorMsg(null);
      setRetryAfterSecs(null);
      setGuardDetail(null);
      refreshIdempotencyKey();
    }
  }, [open, refreshIdempotencyKey]);

  // ── Derived state ─────────────────────────────────────────────────────────

  const requiresOverride = !candidate?.rank || candidate.rank > 3;
  const isOverrideValid = !requiresOverride || overrideReason.trim().length > 0;
  const isReassignReasonValid = mode !== 'reassign' || reassignmentReason !== '';
  const isAckValid = !appointmentBreachShown || appointmentAck.trim().length > 0;
  const canSubmit = isOverrideValid && isReassignReasonValid && isAckValid && !isPending;

  // ── Parts warning ─────────────────────────────────────────────────────────

  const firstPartsWarning = partsWarnings?.[0] ?? null;

  // ── Submit ────────────────────────────────────────────────────────────────

  const handleSubmit = useCallback(() => {
    if (!canSubmit || !candidate) return;

    setOverrideErrors([]);
    setReasonErrors([]);
    setAckErrors([]);

    /** @type {import('../api/useAssignTechnician.js').AssignPayload} */
    const payload = { technicianId: candidate.technicianId };
    if (snapshotId) payload.recommendationSnapshotId = snapshotId;
    if (requiresOverride && overrideReason.trim()) payload.overrideReason = overrideReason.trim();
    if (mode === 'reassign') payload.reassignmentReason = reassignmentReason;
    if (mode === 'reassign' && reassignmentNotes.trim()) payload.reasonNotes = reassignmentNotes.trim();
    if (appointmentBreachShown && appointmentAck.trim()) {
      payload.appointmentImpactAcknowledgement = appointmentAck.trim();
    }

    mutate(payload, {
      onSuccess: () => {
        toast.show({ variant: 'success', message: `${candidate.technicianName} assigned successfully.` });
        onClose();
      },
      onError: (err) => {
        if (err.status === 422) {
          if (err.code === 'CONFIRMED_APPOINTMENT_BREACH') {
            setAppointmentBreachShown(true);
            refreshIdempotencyKey();
          } else {
            // Certification guard or other 422 — terminal
            setGuardDetail(err.message ?? 'Certification requirements were not met.');
            setStep('cert_refused');
          }
        } else if (err.status === 409) {
          setServerErrorMsg(err.message ?? 'This work order was modified by another dispatcher.');
          setStep('conflict');
        } else if (err.status === 429) {
          const secs = err.retryAfterMs != null ? Math.ceil(err.retryAfterMs / 1000) : null;
          setRetryAfterSecs(secs);
          setStep('retry_after');
        } else if (err.status === 400) {
          const byField = {};
          (err.fieldErrors ?? []).forEach(fe => {
            if (!byField[fe.field]) byField[fe.field] = [];
            byField[fe.field].push(fe.message);
          });
          setOverrideErrors(byField['overrideReason'] ?? []);
          setReasonErrors(byField['reassignmentReason'] ?? []);
          setAckErrors(byField['appointmentImpactAcknowledgement'] ?? []);
        } else {
          setServerErrorMsg(
            err.retryable
              ? `Server error — please retry. (${err.traceId ?? 'no trace'})`
              : (err.message ?? 'An unexpected error occurred.'),
          );
          setStep('error');
        }
      },
    });
  }, [
    canSubmit, candidate, snapshotId, requiresOverride, overrideReason,
    mode, reassignmentReason, reassignmentNotes,
    appointmentBreachShown, appointmentAck,
    mutate, toast, onClose, refreshIdempotencyKey,
  ]);

  const handleConflictRefresh = useCallback(() => {
    if (onRefreshRecommendations) onRefreshRecommendations();
    onClose();
  }, [onRefreshRecommendations, onClose]);

  // ── Render helpers ────────────────────────────────────────────────────────

  const title = mode === 'reassign' ? 'Reassign technician' : 'Confirm assignment';
  const scorePct = candidate?.score != null ? `${(candidate.score * 100).toFixed(1)}%` : null;

  function renderBody() {
    if (step === 'cert_refused') {
      return (
        <div className={styles.terminalRefusal} role="alert">
          <p className={styles.terminalIcon} aria-hidden="true">✕</p>
          <h3 className={styles.terminalTitle}>Assignment not permitted</h3>
          <p className={styles.terminalDetail}>{guardDetail}</p>
          <p className={styles.terminalNote}>
            This technician cannot be assigned to this work order. No client action can override this refusal.
          </p>
        </div>
      );
    }

    if (step === 'conflict') {
      return (
        <div className={styles.conflictPanel} role="alert">
          <p className={styles.conflictTitle}>Work order updated by another dispatcher</p>
          <p className={styles.conflictDetail}>{serverErrorMsg}</p>
          <p>Refresh the recommendation list to see the current state.</p>
        </div>
      );
    }

    if (step === 'retry_after') {
      return (
        <div className={styles.retryPanel} role="status">
          <p>Too many requests. Please wait{retryAfterSecs != null ? ` ${retryAfterSecs} seconds` : ''} before retrying.</p>
        </div>
      );
    }

    if (step === 'error') {
      return (
        <div className={styles.errorPanel} role="alert">
          <p>{serverErrorMsg}</p>
        </div>
      );
    }

    // 'form' step
    return (
      <div className={styles.form}>
        {/* Technician summary */}
        {candidate && (
          <dl
            className={styles.technicianSummary}
            aria-label="Selected technician"
          >
            <div className={styles.summaryRow}>
              <dt className={styles.summaryLabel}>Technician</dt>
              <dd className={styles.summaryValue}>{candidate.technicianName}</dd>
            </div>
            {candidate.rank != null && (
              <div className={styles.summaryRow}>
                <dt className={styles.summaryLabel}>Rank</dt>
                <dd className={styles.summaryValue}>#{candidate.rank}</dd>
              </div>
            )}
            {scorePct && (
              <div className={styles.summaryRow}>
                <dt className={styles.summaryLabel}>Composite score</dt>
                <dd className={styles.summaryValue}>{scorePct}</dd>
              </div>
            )}
          </dl>
        )}

        {/* Parts warning */}
        {firstPartsWarning && (
          <PartsWarningNotice warning={firstPartsWarning} id={warnId} />
        )}

        {/* Override reason — required when rank > 3 or absent */}
        {requiresOverride && (
          <OverrideReasonField
            value={overrideReason}
            onChange={setOverrideReason}
            errors={overrideErrors}
          />
        )}

        {/* Reassignment reason — required in reassign mode */}
        {mode === 'reassign' && (
          <ReassignmentReasonSelect
            value={reassignmentReason}
            onChange={setReassignmentReason}
            notes={reassignmentNotes}
            onNotesChange={setReassignmentNotes}
            errors={reasonErrors}
          />
        )}

        {/* Appointment ack — revealed only after server signals breach */}
        {appointmentBreachShown && (
          <AppointmentImpactAcknowledgement
            value={appointmentAck}
            onChange={setAppointmentAck}
            errors={ackErrors}
          />
        )}
      </div>
    );
  }

  function renderFooter() {
    if (step === 'cert_refused') {
      return <Button variant="secondary" onClick={onClose}>Close</Button>;
    }
    if (step === 'conflict') {
      return (
        <>
          <Button variant="secondary" onClick={onClose}>Dismiss</Button>
          <Button variant="primary" onClick={handleConflictRefresh}>
            Refresh recommendations
          </Button>
        </>
      );
    }
    if (step === 'retry_after') {
      return <Button variant="secondary" onClick={onClose}>Close</Button>;
    }
    if (step === 'error') {
      return (
        <>
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={handleSubmit} loading={isPending}>
            Retry
          </Button>
        </>
      );
    }
    // form step
    return (
      <>
        <Button variant="secondary" onClick={onClose} disabled={isPending}>
          Cancel
        </Button>
        <Button
          variant="primary"
          onClick={handleSubmit}
          disabled={!canSubmit}
          loading={isPending}
          aria-describedby={firstPartsWarning ? warnId : undefined}
        >
          {mode === 'reassign' ? 'Reassign' : 'Confirm assignment'}
        </Button>
      </>
    );
  }

  return (
    <Modal open={open} title={title} onClose={onClose} footer={renderFooter()}>
      {renderBody()}
    </Modal>
  );
}
