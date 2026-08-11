/**
 * PartsLoggingPanel — technician parts consumption and return panel.
 *
 * Optimised for a 360 px one-handed viewport with 44 px minimum touch targets.
 * Idempotent: one UUID per submission attempt is generated in state and reused
 * verbatim on retries; regenerated only for a new submission (AC-6).
 *
 * Deliberate offline constraint: no write is accepted offline — the panel
 * renders an explicit not-connected state rather than optimistic success (AC-8).
 *
 * @module features/workorder/technician/PartsLoggingPanel
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { Button } from '../../../components/index.js';
import { FormField } from '../../../components/index.js';
import { StateSurface } from '../../../components/index.js';
import { useNetworkStatus, NetworkOfflineError } from '../../../app/useNetworkStatus.js';
import { newAttemptKey } from '../../../lib/idempotency.js';
import { searchParts, logParts, returnParts, placeOnAwaitingPartsHold } from '../../../api/inventory.js';

import styles from './PartsLoggingPanel.module.css';

/** Reason codes available for technician consumption. */
const REASON_CODES = [
  { value: 'CONSUMED_ON_JOB', label: 'Used on job' },
  { value: 'INSTALLATION', label: 'Installation' },
  { value: 'REPAIR', label: 'Repair' },
  { value: 'REPLACEMENT', label: 'Replacement' },
];

/** Reason codes for returns. */
const RETURN_REASON_CODES = [
  { value: 'NOT_USED', label: 'Not used' },
  { value: 'WRONG_PART', label: 'Wrong part' },
  { value: 'EXCESS', label: 'Excess stock' },
];

/**
 * A staged line waiting to be submitted.
 * @typedef {{ partId: string, partNumber: string, description: string, availableQuantity: number, quantity: number, reasonCode: string }} StagedLine
 */

/**
 * @param {{
 *   workOrderId: string,
 *   mode?: 'log' | 'return',
 *   onSuccess?: () => void,
 * }} props
 */
export function PartsLoggingPanel({ workOrderId, mode = 'log', onSuccess }) {
  const { isOffline, assertOnline } = useNetworkStatus();
  const qc = useQueryClient();

  // Part search state
  const [searchQuery, setSearchQuery]   = useState('');
  const [searchResults, setSearchResults] = useState(/** @type {import('../../../api/inventory.js').PartSearchResult[]} */ ([]));
  const [searching, setSearching]       = useState(false);
  const [searchEmpty, setSearchEmpty]   = useState(false);
  const debounceRef = useRef(null);

  // Staged lines
  const [staged, setStaged] = useState(/** @type {StagedLine[]} */ ([]));

  // Per-line quantity/reason inputs
  const [lineInputs, setLineInputs] = useState(/** @type {Record<string, { quantity: string, reasonCode: string, error: string | null }>} */ ({}));

  // Submission state
  const [attemptKey, setAttemptKey] = useState(() => newAttemptKey());
  const [submitError, setSubmitError]   = useState(/** @type {string | null} */ (null));
  const [insufficientLines, setInsufficientLines] = useState(/** @type {Array<{ partId: string, requested: number, available: number }> | null} */ (null));
  const [notConnected, setNotConnected] = useState(false);
  const [submitted, setSubmitted]       = useState(false);

  // Hold mutation — wired to AWAITING_PARTS transition endpoint
  const holdMutation = useMutation({
    mutationFn: () => placeOnAwaitingPartsHold(workOrderId, newAttemptKey()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['work-order', workOrderId] });
      if (onSuccess) onSuccess();
    },
  });

  // Submit mutation
  const submitFn = mode === 'log' ? logParts : returnParts;
  const submitMutation = useMutation({
    mutationFn: (body) => submitFn(body, attemptKey),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['work-order-parts', workOrderId] });
      qc.invalidateQueries({ queryKey: ['stock-positions'] });
      qc.invalidateQueries({ queryKey: ['movements'] });
      setSubmitted(true);
      setStaged([]);
      setLineInputs({});
      if (onSuccess) onSuccess();
    },
    onError: (err) => {
      // NetworkOfflineError (from assertOnline) or status 0 (from apiFetch network failure)
      if (err instanceof NetworkOfflineError || err?.status === 0) {
        setNotConnected(true);
        return;
      }
      if (err?.status === 422 && err?.code === 'INSUFFICIENT_STOCK') {
        setInsufficientLines(err?.fieldErrors?.map((fe) => ({
          partId: fe.field,
          requested: fe.requested ?? 0,
          available: fe.available ?? 0,
          partNumber: fe.partNumber ?? fe.field,
        })) ?? []);
        setSubmitError(null);
        return;
      }
      if (err?.status === 409) {
        setSubmitError('A conflict occurred — please refresh and retry.');
        return;
      }
      setSubmitError(err?.message ?? 'Submission failed. Please try again.');
    },
  });

  // Debounced part search
  useEffect(() => {
    if (!searchQuery.trim()) {
      setSearchResults([]);
      setSearchEmpty(false);
      return;
    }
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      setSearching(true);
      try {
        const res = await searchParts({ q: searchQuery.trim() });
        const parts = res?.data ?? [];
        setSearchResults(parts);
        setSearchEmpty(parts.length === 0);
      } catch (_) {
        setSearchResults([]);
        setSearchEmpty(false);
      } finally {
        setSearching(false);
      }
    }, 300);
    return () => clearTimeout(debounceRef.current);
  }, [searchQuery]);

  const addLine = useCallback((part) => {
    const alreadyStaged = staged.some((l) => l.partId === part.id);
    if (alreadyStaged) return;
    setStaged((prev) => [
      ...prev,
      {
        partId: part.id,
        partNumber: part.partNumber,
        description: part.description,
        availableQuantity: part.availableQuantity,
        quantity: 1,
        reasonCode: (mode === 'log' ? REASON_CODES : RETURN_REASON_CODES)[0].value,
      },
    ]);
    setLineInputs((prev) => ({
      ...prev,
      [part.id]: { quantity: '1', reasonCode: (mode === 'log' ? REASON_CODES : RETURN_REASON_CODES)[0].value, error: null },
    }));
    setSearchQuery('');
    setSearchResults([]);
  }, [staged, mode]);

  const removeLine = useCallback((partId) => {
    setStaged((prev) => prev.filter((l) => l.partId !== partId));
    setLineInputs((prev) => {
      const next = { ...prev };
      delete next[partId];
      return next;
    });
  }, []);

  const updateLineField = useCallback((partId, field, value) => {
    setLineInputs((prev) => ({
      ...prev,
      [partId]: { ...(prev[partId] ?? {}), [field]: value, error: null },
    }));
  }, []);

  function validateLines() {
    let valid = true;
    const updated = { ...lineInputs };
    staged.forEach((l) => {
      const input = lineInputs[l.partId] ?? {};
      const qty = Number(input.quantity);
      if (!input.quantity || input.quantity.trim() === '') {
        updated[l.partId] = { ...input, error: 'Quantity is required.' };
        valid = false;
      } else if (!Number.isInteger(qty) || qty <= 0) {
        updated[l.partId] = { ...input, error: 'Enter a whole number greater than 0.' };
        valid = false;
      } else if (mode === 'log' && qty > l.availableQuantity) {
        updated[l.partId] = { ...input, error: `Only ${l.availableQuantity} available.` };
        valid = false;
      }
    });
    setLineInputs(updated);
    return valid;
  }

  function handleSubmit(e) {
    e.preventDefault();
    if (staged.length === 0) return;
    if (!validateLines()) return;

    setSubmitError(null);
    setInsufficientLines(null);
    setNotConnected(false);

    try {
      assertOnline();
    } catch (err) {
      if (err instanceof NetworkOfflineError) {
        setNotConnected(true);
        return;
      }
    }

    const lines = staged.map((l) => ({
      partId: l.partId,
      quantity: Number(lineInputs[l.partId]?.quantity ?? l.quantity),
      reasonCode: lineInputs[l.partId]?.reasonCode ?? l.reasonCode,
    }));

    submitMutation.mutate({ workOrderId, lines });
  }

  function handleRetry() {
    setNotConnected(false);
    // Reuse same attemptKey — same attempt
  }

  function handleNewSubmission() {
    setSubmitted(false);
    setSubmitError(null);
    setInsufficientLines(null);
    setNotConnected(false);
    setAttemptKey(newAttemptKey());
    setStaged([]);
    setLineInputs({});
  }

  const reasonCodes = mode === 'log' ? REASON_CODES : RETURN_REASON_CODES;
  const panelTitle  = mode === 'log' ? 'Log Parts' : 'Return Parts';

  if (submitted) {
    return (
      <div className={styles.panel}>
        <StateSurface
          variant="empty"
          title={mode === 'log' ? 'Parts logged' : 'Parts returned'}
          description="The submission was recorded successfully."
        />
        <div className={styles.actions}>
          <Button variant="secondary" onClick={handleNewSubmission}>
            {mode === 'log' ? 'Log more parts' : 'Return more parts'}
          </Button>
        </div>
      </div>
    );
  }

  if (notConnected) {
    return (
      <div className={styles.panel}>
        <StateSurface
          variant="error"
          title="Not connected"
          description="Your submission could not be sent. No parts have been recorded. Please check your connection and try again."
          onRetry={handleRetry}
          retryLabel="Retry submission"
        />
      </div>
    );
  }

  return (
    <div className={styles.panel}>
      <h3 className={styles.panelTitle}>{panelTitle}</h3>

      {/* Part search */}
      <div className={styles.searchSection}>
        <FormField
          label="Search parts"
          help="Enter part number or description"
        >
          <input
            type="search"
            className={styles.searchInput}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Part number or description"
            autoComplete="off"
            aria-busy={searching}
          />
        </FormField>

        {searching && (
          <p className={styles.searchStatus} role="status" aria-live="polite">
            Searching…
          </p>
        )}

        {searchEmpty && !searching && (
          <div className={styles.searchEmpty} role="status" aria-live="polite">
            <p>No parts found for &ldquo;{searchQuery}&rdquo;.</p>
            <p className={styles.searchEmptyHint}>Try a different part number or description.</p>
          </div>
        )}

        {searchResults.length > 0 && (
          <ul className={styles.searchResults} role="listbox" aria-label="Part search results">
            {searchResults.map((part) => (
              <li key={part.id} role="option">
                <button
                  type="button"
                  className={styles.searchResultBtn}
                  onClick={() => addLine(part)}
                  aria-label={`Add ${part.partNumber} — ${part.description}`}
                  disabled={staged.some((l) => l.partId === part.id)}
                >
                  <span className={styles.partNumber}>{part.partNumber}</span>
                  <span className={styles.partDesc}>{part.description}</span>
                  <span className={styles.partAvail} aria-label={`${part.availableQuantity} available`}>
                    Avail: {part.availableQuantity}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Staged lines */}
      {staged.length > 0 && (
        <form onSubmit={handleSubmit} aria-label={panelTitle} noValidate>
          <ul className={styles.stagedList} aria-label="Parts to submit">
            {staged.map((line) => {
              const input = lineInputs[line.partId] ?? {};
              return (
                <li key={line.partId} className={styles.stagedItem}>
                  <div className={styles.stagedHeader}>
                    <span className={styles.stagedPartNum}>{line.partNumber}</span>
                    <button
                      type="button"
                      className={styles.removeBtn}
                      onClick={() => removeLine(line.partId)}
                      aria-label={`Remove ${line.partNumber}`}
                    >
                      ✕
                    </button>
                  </div>
                  <p className={styles.stagedDesc} title={line.description}>{line.description}</p>

                  <div className={styles.lineFields}>
                    <FormField
                      label="Qty"
                      errors={input.error ? [input.error] : []}
                    >
                      <input
                        type="number"
                        inputMode="numeric"
                        className={[styles.qtyInput, input.error ? styles.qtyInputError : ''].filter(Boolean).join(' ')}
                        value={input.quantity ?? '1'}
                        min="1"
                        step="1"
                        max={mode === 'log' ? line.availableQuantity : undefined}
                        onChange={(e) => updateLineField(line.partId, 'quantity', e.target.value)}
                      />
                    </FormField>

                    <FormField label="Reason">
                      <select
                        className={styles.reasonSelect}
                        value={input.reasonCode ?? reasonCodes[0].value}
                        onChange={(e) => updateLineField(line.partId, 'reasonCode', e.target.value)}
                      >
                        {reasonCodes.map((rc) => (
                          <option key={rc.value} value={rc.value}>{rc.label}</option>
                        ))}
                      </select>
                    </FormField>
                  </div>
                </li>
              );
            })}
          </ul>

          {/* 422 INSUFFICIENT_STOCK — per-line detail + hold action */}
          {insufficientLines && (
            <div className={styles.insufficientError} role="alert">
              <p className={styles.insufficientTitle}>
                <span aria-hidden="true">⚠ </span>
                Insufficient stock — no lines were applied.
              </p>
              <ul className={styles.insufficientList}>
                {insufficientLines.map((l) => (
                  <li key={l.partId}>
                    <strong>{l.partNumber ?? l.partId}</strong>: requested {l.requested}, available {l.available}
                  </li>
                ))}
              </ul>
              <p className={styles.insufficientHint}>
                You can place this work order on hold until stock is replenished.
              </p>
              <Button
                variant="secondary"
                onClick={() => holdMutation.mutate()}
                disabled={holdMutation.isPending}
              >
                {holdMutation.isPending ? 'Placing on hold…' : 'Place on hold (Awaiting Parts)'}
              </Button>
            </div>
          )}

          {submitError && (
            <p className={styles.submitError} role="alert">{submitError}</p>
          )}

          <div className={styles.actions}>
            <Button
              type="submit"
              variant="primary"
              disabled={submitMutation.isPending || staged.length === 0 || isOffline}
              aria-busy={submitMutation.isPending}
            >
              {submitMutation.isPending
                ? (mode === 'log' ? 'Logging…' : 'Returning…')
                : (mode === 'log' ? `Submit ${staged.length} line${staged.length === 1 ? '' : 's'}` : `Return ${staged.length} line${staged.length === 1 ? '' : 's'}`)}
            </Button>
          </div>
        </form>
      )}

      {staged.length === 0 && !searching && searchResults.length === 0 && (
        <p className={styles.emptyStaged}>
          Search for parts above and tap to add them to your list.
        </p>
      )}
    </div>
  );
}
