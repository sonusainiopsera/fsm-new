/**
 * useCopilotStream — explicit state machine for the copilot SSE stream.
 *
 * Auth flow:
 *   1. POST /copilot/stream-ticket   → { ticket, streamUrl }  (single-use token, never in URL as Bearer)
 *   2. new EventSource(streamUrl + '?ticket=' + ticket)       (ticket is a short-lived opaque token)
 *
 * Named SSE event listeners:
 *   token           — { chunkIndex, text }   accumulate into answer
 *   complete        — { interactionId, basis } — terminal success
 *   no_grounded_basis — terminal refusal
 *   degraded        — terminal provider failure
 *   error           — terminal stream error
 *
 * INP protection: chunks are batched on requestAnimationFrame; state is only
 * flushed once per frame instead of once per token event.
 *
 * CLS protection: the answer region height is reserved by the sheet (skeleton);
 * this hook never inserts elements above already-rendered text.
 *
 * Out-of-order chunks: sorted by chunkIndex before joining so duplicate or
 * out-of-order delivery is idempotent.
 *
 * @module features/copilot/useCopilotStream
 */

import { useState, useRef, useCallback, useEffect } from 'react';
import { apiFetch } from '../../api/http.js';
import {
  IDLE, STREAMING, COMPLETE, REFUSED, DEGRADED, CAPPED, PARTIAL,
} from './copilotStates.js';

const STREAM_TICKET_PATH = '/copilot/stream-ticket';

/**
 * @typedef {import('./copilotStates.js').IDLE
 *   | import('./copilotStates.js').STREAMING
 *   | import('./copilotStates.js').COMPLETE
 *   | import('./copilotStates.js').REFUSED
 *   | import('./copilotStates.js').DEGRADED
 *   | import('./copilotStates.js').CAPPED
 *   | import('./copilotStates.js').PARTIAL} CopilotState
 */

/**
 * @typedef {{
 *   assetId: string | null,
 *   assetName: string | null,
 *   priorWorkOrders: Array<{ id: string, reference: string, summary: string }>,
 * }} CopilotBasis
 */

/**
 * @typedef {{
 *   state: CopilotState,
 *   answer: string,
 *   basis: CopilotBasis | null,
 *   interactionId: string | null,
 *   retryAfter: number | null,
 *   start: (workOrderId: string, question: string) => void,
 *   reset: () => void,
 * }} CopilotStreamResult
 */

/**
 * Strip control characters that must not appear in rendered text.
 * Preserves newlines, tabs, and printable Unicode.
 *
 * @param {string} text
 * @returns {string}
 */
function sanitizeChunk(text) {
  // Remove ASCII control chars except \t (9), \n (10), \r (13)
  return String(text).replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '');
}

/**
 * Manages the full copilot SSE stream lifecycle with an explicit state machine.
 *
 * @returns {CopilotStreamResult}
 */
export function useCopilotStream() {
  const [state, setState]               = useState(/** @type {CopilotState} */ (IDLE));
  const [answer, setAnswer]             = useState('');
  const [basis, setBasis]               = useState(/** @type {CopilotBasis | null} */ (null));
  const [interactionId, setInteractionId] = useState(/** @type {string | null} */ (null));
  const [retryAfter, setRetryAfter]     = useState(/** @type {number | null} */ (null));

  // Accumulated chunks keyed by chunkIndex for dedup / ordering
  const chunksRef   = useRef(/** @type {Map<number, string>} */ (new Map()));
  const esRef       = useRef(/** @type {EventSource | null} */ (null));
  const rafRef      = useRef(/** @type {number | null} */ (null));
  const abortRef    = useRef(false);

  /** Close and nullify the EventSource. */
  const closeEs = useCallback(() => {
    if (esRef.current) {
      esRef.current.close();
      esRef.current = null;
    }
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
  }, []);

  /** Flush accumulated chunks to answer state on next animation frame. */
  const scheduleFlush = useCallback(() => {
    if (rafRef.current !== null) return; // already scheduled
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null;
      if (abortRef.current) return;
      const sorted = Array.from(chunksRef.current.entries())
        .sort(([a], [b]) => a - b)
        .map(([, text]) => text)
        .join('');
      setAnswer(sorted);
    });
  }, []);

  const reset = useCallback(() => {
    abortRef.current = true;
    closeEs();
    chunksRef.current.clear();
    abortRef.current = false;
    setState(IDLE);
    setAnswer('');
    setBasis(null);
    setInteractionId(null);
    setRetryAfter(null);
  }, [closeEs]);

  const start = useCallback(async (workOrderId, question) => {
    if (state === STREAMING) return; // prevent double-start
    reset();
    setState(STREAMING);

    // Step 1: fetch single-use stream ticket
    let ticket, streamUrl;
    try {
      const resp = await apiFetch(STREAM_TICKET_PATH, {
        method: 'POST',
        body: JSON.stringify({ workOrderId, question }),
      });
      ticket    = /** @type {any} */ (resp).ticket;
      streamUrl = /** @type {any} */ (resp).streamUrl ?? '/api/v1/copilot/stream';
    } catch (err) {
      if (abortRef.current) return;
      const status = /** @type {any} */ (err)?.status;
      if (status === 429) {
        const retryAfterSec = parseInt(
          /** @type {any} */ (err)?.headers?.get?.('Retry-After') ?? '60', 10,
        );
        setRetryAfter(retryAfterSec);
        setState(CAPPED);
      } else {
        setState(DEGRADED);
      }
      return;
    }

    if (abortRef.current) return;

    // Step 2: open EventSource with ticket as query param (NOT Bearer in URL)
    const url = `${streamUrl}?ticket=${encodeURIComponent(ticket)}`;
    let es;
    try {
      es = new EventSource(url);
    } catch {
      setState(DEGRADED);
      return;
    }
    esRef.current = es;

    es.addEventListener('token', (e) => {
      if (abortRef.current) return;
      try {
        const { chunkIndex, text } = JSON.parse(e.data);
        chunksRef.current.set(chunkIndex, sanitizeChunk(text));
        scheduleFlush();
      } catch { /* malformed event — skip */ }
    });

    es.addEventListener('complete', (e) => {
      if (abortRef.current) return;
      try {
        const payload = JSON.parse(e.data);
        setInteractionId(payload.interactionId ?? null);
        setBasis(payload.basis ?? null);
      } catch { /* ignore */ }
      // Final flush
      const sorted = Array.from(chunksRef.current.entries())
        .sort(([a], [b]) => a - b)
        .map(([, text]) => text)
        .join('');
      setAnswer(sorted);
      closeEs();
      setState(COMPLETE);
    });

    es.addEventListener('no_grounded_basis', () => {
      if (abortRef.current) return;
      closeEs();
      setState(REFUSED);
    });

    es.addEventListener('degraded', () => {
      if (abortRef.current) return;
      closeEs();
      // Retain partial answer if any
      const hasPartial = chunksRef.current.size > 0;
      setState(hasPartial ? PARTIAL : DEGRADED);
    });

    es.addEventListener('error', () => {
      if (abortRef.current) return;
      closeEs();
      const hasPartial = chunksRef.current.size > 0;
      setState(hasPartial ? PARTIAL : DEGRADED);
    });

    // Fallback: browser onerror (e.g. immediate connect fail)
    es.onerror = () => {
      if (abortRef.current) return;
      if (esRef.current === es) {
        closeEs();
        const hasPartial = chunksRef.current.size > 0;
        setState(hasPartial ? PARTIAL : DEGRADED);
      }
    };
  }, [state, reset, closeEs, scheduleFlush]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      abortRef.current = true;
      closeEs();
    };
  }, [closeEs]);

  return { state, answer, basis, interactionId, retryAfter, start, reset };
}
