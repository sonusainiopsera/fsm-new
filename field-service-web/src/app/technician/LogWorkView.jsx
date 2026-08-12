/**
 * LogWorkView — log labour time and parts consumption, then complete the job.
 *
 * Composed of:
 * - TimeEntryCard: labour time entry (duration or start/end)
 * - PartsRowsList: repeatable parts rows bound to vehicle stock
 * - HoldReasonSheet: pre-populated with AWAITING_PARTS on shortfall
 * - Sticky "Complete job" bar
 *
 * All mutations use TanStack Query useMutation with retry disabled.
 * Each has a stable per-intent idempotency key generated at submit time,
 * held in state and reused verbatim on retries.
 *
 * On successful completion, the day-list and job-detail queries are invalidated
 * and the technician is navigated back to the day list.
 *
 * @module app/technician/LogWorkView
 */

import React, { useState, useCallback, useReducer } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../../api/http.js';
import { newAttemptKey } from '../../lib/idempotency.js';
import { useConnectivityContext } from './ConnectivityContext.js';
import { mapApiError } from '../../shared/api/errorMapping.js';
import { TimeEntryCard } from './components/TimeEntryCard.jsx';
import { PartsRowsList, partsRowReducer, createEmptyRow, buildShortfallMessage } from './components/PartsRowsList.jsx';
import { HoldReasonSheet } from './components/HoldReasonSheet.jsx';
import { useJobDetail } from './useJobDetail.js';
import { LoadingState, ErrorState } from '../../components/index.js';
import styles from './LogWorkView.module.css';

export function LogWorkView() {
  const { jobId } = useParams();
  const navigate  = useNavigate();
  const qc        = useQueryClient();
  const { assertOnline } = useConnectivityContext();

  const { data: job, isLoading } = useJobDetail(jobId);

  // Labour state
  const [labourKey, setLabourKey]         = useState(() => newAttemptKey());
  const [labourSubmitted, setLabourSubmitted] = useState(false);
  const [labourError, setLabourError]     = useState(null);

  // Parts state
  const [rows, dispatch]       = useReducer(partsRowReducer, []);
  const [partsKey, setPartsKey]= useState(() => newAttemptKey());
  const [partsSubmitted, setPartsSubmitted] = useState(false);

  // Complete state
  const [completeKey, setCompleteKey]   = useState(() => newAttemptKey());
  const [completeError, setCompleteError] = useState(null);

  // Hold sheet for shortfall path
  const [holdSheetOpen, setHoldSheetOpen]     = useState(false);
  const [holdSheetNote, setHoldSheetNote]     = useState('');

  const holdReasons = job?.holdReasons ?? [];
  const AWAITING_PARTS_CODE = 'AWAITING_PARTS';

  // Labour mutation
  const labourMutation = useMutation({
    mutationFn: ({ durationMinutes, startedAt, endedAt, note }) =>
      apiFetch(`/work-orders/${jobId}/labour`, {
        method: 'POST',
        body: JSON.stringify({ durationMinutes, startedAt, endedAt, note }),
        headers: { 'Idempotency-Key': labourKey },
      }),
    retry: false,
    onSuccess: () => {
      setLabourSubmitted(true);
      setLabourError(null);
    },
    onError: (error) => {
      const mapped = mapApiError(error);
      setLabourError(mapped.message);
    },
  });

  // Parts consumption mutation
  const partsMutation = useMutation({
    mutationFn: ({ lines, locationId }) =>
      apiFetch(`/work-orders/${jobId}/parts`, {
        method: 'POST',
        body: JSON.stringify({ lines, stockLocationId: locationId }),
        headers: { 'Idempotency-Key': partsKey },
      }),
    retry: false,
    onSuccess: () => {
      setPartsSubmitted(true);
      dispatch({ type: 'CLEAR_ERRORS' });
    },
    onError: (error) => {
      if (error?.status === 422 && error?.code === 'INSUFFICIENT_STOCK') {
        const fieldErrors = error.fieldErrors ?? [];
        fieldErrors.forEach((fe, idx) => {
          const row = rows[idx];
          if (row) {
            dispatch({ type: 'SET_ERROR', payload: { rowId: row.rowId, error: fe.message } });
          }
        });
      }
    },
  });

  // Complete transition mutation
  const completeMutation = useMutation({
    mutationFn: () =>
      apiFetch(`/work-orders/${jobId}/transitions`, {
        method: 'POST',
        body: JSON.stringify({ event: 'COMPLETE', expectedVersion: job?.version }),
        headers: { 'Idempotency-Key': completeKey },
      }),
    retry: false,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['technician', 'jobs', jobId] });
      qc.invalidateQueries({ queryKey: ['technician', 'day-list'] });
      navigate('/technician');
    },
    onError: (error) => {
      const mapped = mapApiError(error);
      setCompleteError(mapped.message);
    },
  });

  // Hold mutation (for shortfall path)
  const holdMutation = useMutation({
    mutationFn: ({ reasonCode, note }) =>
      apiFetch(`/work-orders/${jobId}/transitions`, {
        method: 'POST',
        body: JSON.stringify({
          event: 'HOLD',
          holdReasonCode: reasonCode,
          note,
          expectedVersion: job?.version,
        }),
        headers: { 'Idempotency-Key': newAttemptKey() },
      }),
    retry: false,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['technician', 'jobs', jobId] });
      qc.invalidateQueries({ queryKey: ['technician', 'day-list'] });
      navigate('/technician');
    },
  });

  const handleLabourSubmit = useCallback((entry) => {
    assertOnline();
    labourMutation.mutate(entry);
  }, [assertOnline, labourMutation]);

  const handlePartsSubmit = useCallback(() => {
    assertOnline();
    // Collect valid rows
    const lines = rows
      .filter((r) => r.partId && parseInt(r.quantity, 10) > 0)
      .map((r) => ({ partId: r.partId, quantity: parseInt(r.quantity, 10), reasonCode: 'CONSUMED_ON_JOB' }));
    if (lines.length === 0) {
      setPartsSubmitted(true);
      return;
    }
    // All rows must use same locationId (vehicle)
    const { locationId } = (rows[0]?.partId) ? { locationId: null } : {};
    partsMutation.mutate({ lines, locationId: null });
  }, [assertOnline, partsMutation, rows]);

  const handleShortfall = useCallback((partCode, shortfallDetail) => {
    setHoldSheetNote(buildShortfallMessage(partCode, shortfallDetail));
    setHoldSheetOpen(true);
  }, []);

  const handleHoldConfirm = useCallback((reasonCode, note) => {
    setHoldSheetOpen(false);
    holdMutation.mutate({ reasonCode, note });
  }, [holdMutation]);

  const handleComplete = useCallback(() => {
    assertOnline();
    setCompleteError(null);
    completeMutation.mutate();
  }, [assertOnline, completeMutation]);

  if (isLoading) return <LoadingState />;
  if (!job) return <ErrorState title="Job not found" action={{ label: 'Back', onClick: () => navigate('/technician') }} />;

  const anyPending = labourMutation.isPending || partsMutation.isPending || completeMutation.isPending;

  return (
    <div className={styles.screen}>
      <header className={styles.header}>
        <button
          type="button"
          className={styles.backBtn}
          onClick={() => navigate(`/technician/${jobId}`)}
          aria-label="Back to job detail"
        >
          ‹
        </button>
        <span className={styles.title}>Log work — {job.reference}</span>
      </header>

      <main className={styles.content}>
        <TimeEntryCard
          onSubmit={handleLabourSubmit}
          isPending={labourMutation.isPending}
          error={labourError}
          submitted={labourSubmitted}
        />

        <PartsRowsList
          rows={rows}
          dispatch={dispatch}
          onShortfall={handleShortfall}
          isPending={partsMutation.isPending}
        />

        {rows.length > 0 && !partsSubmitted && (
          <button
            type="button"
            className={styles.partsSubmitBtn}
            onClick={handlePartsSubmit}
            disabled={anyPending}
            aria-busy={partsMutation.isPending}
          >
            {partsMutation.isPending ? 'Saving parts…' : 'Save parts'}
          </button>
        )}
      </main>

      {completeError && (
        <div className={styles.completeError} role="alert" aria-live="assertive">
          {completeError}
        </div>
      )}

      <div className={styles.completeBar}>
        <button
          type="button"
          className={styles.completeBtn}
          onClick={handleComplete}
          disabled={anyPending}
          aria-busy={completeMutation.isPending}
        >
          {completeMutation.isPending ? 'Completing…' : 'Complete job'}
        </button>
      </div>

      <HoldReasonSheet
        open={holdSheetOpen}
        holdReasons={holdReasons.length > 0 ? holdReasons : [{ code: AWAITING_PARTS_CODE, label: 'Awaiting parts', sortOrder: 10 }]}
        onConfirm={handleHoldConfirm}
        onCancel={() => setHoldSheetOpen(false)}
        isPending={holdMutation.isPending}
        defaultReasonCode={AWAITING_PARTS_CODE}
        defaultNote={holdSheetNote}
      />
    </div>
  );
}
