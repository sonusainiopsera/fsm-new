/**
 * ErasureConfirmDialog — destructive confirmation for subject data erasure.
 *
 * Requires the user to type "CONFIRM" before the action button is enabled.
 * States irreversibility and what non-identifying records are retained.
 * Submits with a stable idempotency key so double-click / retry cannot double-submit.
 * Surfaces 422 guard-refusal reasons verbatim from the server message.
 *
 * Saturated colour (--color-feedback-danger) is used only for this dialog and
 * the at-risk countdown, per BR-34 (visual standards).
 *
 * @module features/privacy/ErasureConfirmDialog
 */

import React, { useState, useEffect, useRef } from 'react';
import { useMutation } from '@tanstack/react-query';

import { Modal, Button } from '../../components/index.js';
import { initiateErasure } from '../../api/privacyAdmin.js';
import { newAttemptKey }   from '../../lib/idempotency.js';

import styles from '../admin/admin.module.css';

const CONFIRMATION_TOKEN = 'CONFIRM';

/**
 * @param {{
 *   open: boolean,
 *   dsarRequest: object | null,
 *   onClose: () => void,
 *   onSuccess: (result: object) => void,
 * }} props
 */
export function ErasureConfirmDialog({ open, dsarRequest, onClose, onSuccess }) {
  const [confirmInput, setConfirmInput] = useState('');
  const [idempotencyKey]                = useState(() => newAttemptKey());
  const inputRef = useRef(null);

  useEffect(() => {
    if (open) {
      setConfirmInput('');
      // Focus the text input when the dialog opens
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  }, [open]);

  const erasureMutation = useMutation({
    mutationFn: ({ subjectType, subjectId, dsarRequestId, note }) =>
      initiateErasure(subjectType, subjectId, {
        dsarRequestId,
        confirmation: CONFIRMATION_TOKEN,
        note,
      }, { idempotencyKey }),
    onSuccess: (result) => {
      onSuccess(result);
      onClose();
    },
  });

  const handleConfirm = () => {
    if (!dsarRequest) return;
    erasureMutation.mutate({
      subjectType: dsarRequest.subjectType,
      subjectId: dsarRequest.subjectId,
      dsarRequestId: dsarRequest.id,
      note: `Erasure initiated via privacy admin console by authorised operator.`,
    });
  };

  const isValid = confirmInput === CONFIRMATION_TOKEN;
  const refusalMessage = erasureMutation.error?.status === 422
    ? erasureMutation.error?.message
    : null;

  return (
    <Modal
      open={open}
      title="Confirm Data Erasure"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={erasureMutation.isPending}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={handleConfirm}
            disabled={!isValid || erasureMutation.isPending}
          >
            {erasureMutation.isPending ? 'Erasing…' : 'Erase data'}
          </Button>
        </>
      }
    >
      <div className={styles.erasureWarning} role="note">
        <strong>This action is irreversible.</strong>
        <p>
          The subject's envelope encryption key will be permanently destroyed.
          All personal-data fields across live rows, audit history, backups and caches
          will become permanently unreadable.
        </p>
        <p>
          Non-identifying transaction records — work order counts, closure timestamps,
          SLA compliance figures and revision numbers — will be retained as required
          by the data retention policy.
        </p>
        <p>
          This action requires a verified DSAR request of type <strong>ERASURE</strong>.
        </p>
      </div>

      {dsarRequest && (
        <dl className={styles.erasureMeta}>
          <dt>Subject type</dt><dd>{dsarRequest.subjectType}</dd>
          <dt>Subject ID</dt><dd><code>{dsarRequest.subjectId}</code></dd>
          <dt>DSAR ID</dt><dd><code>{dsarRequest.id}</code></dd>
        </dl>
      )}

      {refusalMessage && (
        <div className={styles.guardRefusal} role="alert">
          <strong>Erasure refused:</strong> {refusalMessage}
        </div>
      )}
      {erasureMutation.isError && !refusalMessage && (
        <div className={styles.errorText} role="alert">
          An error occurred. Please try again or contact support.
        </div>
      )}

      <div className={styles.confirmationField}>
        <label htmlFor="erasure-confirm-input" className={styles.confirmationLabel}>
          Type <strong>{CONFIRMATION_TOKEN}</strong> to enable the erase button
        </label>
        <input
          ref={inputRef}
          id="erasure-confirm-input"
          type="text"
          value={confirmInput}
          onChange={(e) => setConfirmInput(e.target.value)}
          className={styles.input}
          aria-invalid={confirmInput.length > 0 && !isValid}
          aria-describedby="erasure-confirm-hint"
          autoComplete="off"
        />
        <span id="erasure-confirm-hint" className={styles.sr_only}>
          Enter the word CONFIRM to enable the irreversible erase action
        </span>
      </div>
    </Modal>
  );
}
