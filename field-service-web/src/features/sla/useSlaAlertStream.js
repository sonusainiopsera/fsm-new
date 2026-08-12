/**
 * useSlaAlertStream — full SSE connection lifecycle for SLA risk alerts.
 *
 * Contract:
 * - Fresh single-use ticket per connection attempt (never reused).
 * - EventSource closed immediately on any error (no browser auto-retry).
 * - Capped jittered exponential backoff for reconnection.
 * - Last-Event-ID tracked and re-sent on reconnect for replay.
 * - Heartbeat staleness detection: stale after (heartbeatIntervalMs × staleMultiplier).
 * - 401 on ticket → apiFetch single-flight refresh → one retry; further failure → stale.
 * - 429 on ticket → surface stale, wait Retry-After.
 * - Online/offline and visibility-change aware: no wasted attempts while offline/hidden.
 * - SSE events debounce TanStack Query invalidations so bursts don't thrash the UI.
 *
 * @module features/sla/useSlaAlertStream
 */

import { useState, useEffect, useRef, useCallback } from 'react';

import { apiFetch } from '../../api/http.js';
import { queryClient } from '../../api/queryClient.js';

const BASE_BACKOFF_MS  = 1_000;
const MAX_BACKOFF_MS   = 30_000;
const DEBOUNCE_MS      = 200;

/**
 * @typedef {'live' | 'reconnecting' | 'stale'} StreamStatus
 */

/**
 * Manages the full SSE connection lifecycle for SLA risk alerts.
 *
 * @param {object}  [opts]
 * @param {string}  [opts.streamPath='/streams/sla-alerts']
 * @param {number}  [opts.heartbeatIntervalMs=30000]
 * @param {number}  [opts.staleMultiplier=3]          Number of missed heartbeats before stale.
 * @returns {{ status: StreamStatus, lastEventAt: Date | null, refresh: () => void }}
 */
export function useSlaAlertStream({
  streamPath        = '/streams/sla-alerts',
  heartbeatIntervalMs = 30_000,
  staleMultiplier   = 3,
} = {}) {
  const [status, setStatus]           = useState(/** @type {StreamStatus} */ ('reconnecting'));
  const [lastEventAt, setLastEventAt] = useState(/** @type {Date | null} */ (null));

  // Manual refresh increments this to re-run the effect.
  const [refreshTick, setRefreshTick] = useState(0);

  // Keep prop values accessible inside the effect without adding them as deps
  // (they don't change identity, but we want a stable effect).
  const configRef = useRef({ streamPath, heartbeatIntervalMs, staleMultiplier });
  configRef.current = { streamPath, heartbeatIntervalMs, staleMultiplier };

  const refresh = useCallback(() => {
    setStatus('reconnecting');
    setRefreshTick((n) => n + 1);
  }, []);

  useEffect(() => {
    const { streamPath: path, heartbeatIntervalMs: hbMs, staleMultiplier: staleMult } =
      configRef.current;

    // ── Lifecycle state (closure-local, stable across re-renders) ──────────
    let stopped         = false;
    let es              = null;
    let attempt         = 0;
    let lastEventId     = null;
    let backoffTimer    = null;
    let stalenessTimer  = null;
    let debounceTimer   = null;
    const invalidQueue  = new Set();

    // ── Helpers ────────────────────────────────────────────────────────────

    function resetStalenessTimer() {
      clearTimeout(stalenessTimer);
      stalenessTimer = setTimeout(() => {
        if (!stopped) setStatus('stale');
      }, hbMs * staleMult);
    }

    function flushInvalidations() {
      const ids = [...invalidQueue];
      invalidQueue.clear();
      queryClient.invalidateQueries({ queryKey: ['work-orders'] });
      queryClient.invalidateQueries({ queryKey: ['sla-alerts'] });
      ids.forEach((id) => id && queryClient.invalidateQueries({ queryKey: ['work-order', id] }));
    }

    function scheduleInvalidation(workOrderId) {
      invalidQueue.add(workOrderId);
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(flushInvalidations, DEBOUNCE_MS);
    }

    function backoffDelay() {
      const base  = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * Math.pow(2, attempt));
      const delay = Math.round(base * (0.5 + Math.random() * 0.5));
      attempt++;
      return delay;
    }

    function scheduleReconnect() {
      if (stopped) return;
      backoffTimer = setTimeout(connect, backoffDelay());
    }

    // ── Connection ─────────────────────────────────────────────────────────

    async function connect() {
      if (stopped) return;

      // Acquire a fresh single-use ticket via the authenticated client.
      // apiFetch already handles one silent 401 → refresh → retry internally.
      let ticket;
      try {
        const resp = await apiFetch('/auth/stream-ticket', { method: 'POST' });
        ticket = resp?.ticket;
        if (!ticket || typeof ticket !== 'string') throw new Error('stream-ticket missing ticket field');
        attempt = 0; // reset backoff on successful ticket
      } catch (err) {
        if (stopped) return;
        const httpStatus = err?.status;

        // 403 → no permission for SSE; go stale indefinitely (don't loop).
        if (httpStatus === 403) {
          setStatus('stale');
          return;
        }

        // 429 → back off for Retry-After, then try again.
        if (httpStatus === 429) {
          const retryAfterSec = parseInt(err?.retryAfter ?? '60', 10) || 60;
          setStatus('stale');
          if (!stopped) backoffTimer = setTimeout(connect, retryAfterSec * 1_000);
          return;
        }

        scheduleReconnect();
        return;
      }

      if (stopped) return;

      // Build the EventSource URL.  Access token never appears in the URL.
      let url = `/api/v1${path}?ticket=${encodeURIComponent(ticket)}`;
      if (lastEventId) url += `&lastEventId=${encodeURIComponent(lastEventId)}`;

      try {
        es = new EventSource(url);
      } catch (_) {
        scheduleReconnect();
        return;
      }

      // ── EventSource handlers ──────────────────────────────────────────

      es.onopen = () => {
        if (stopped) { es.close(); return; }
        attempt = 0;
        setStatus('live');
        setLastEventAt(new Date());
        resetStalenessTimer();
      };

      /** Called for every frame (heartbeat or named event). */
      function onFrame(event) {
        if (stopped) return;
        if (event.lastEventId) lastEventId = event.lastEventId;
        setLastEventAt(new Date());
        resetStalenessTimer();
      }

      /** Called for alert events that should invalidate queries. */
      function onAlertEvent(event) {
        onFrame(event);
        let data;
        try { data = JSON.parse(event.data); } catch (_) {}
        scheduleInvalidation(data?.workOrderId ?? null);
      }

      // Unnamed message (heartbeat frame or fallback)
      es.onmessage = onFrame;

      // Named events defined in the server SSE contract
      es.addEventListener('heartbeat',    onFrame);
      es.addEventListener('at-risk',      onAlertEvent);
      es.addEventListener('breach',       onAlertEvent);
      es.addEventListener('sla-updated',  onAlertEvent);
      es.addEventListener('state-change', onAlertEvent);

      // Error — close immediately (never allow browser auto-retry with consumed ticket).
      es.onerror = () => {
        es.close();
        es = null;
        if (stopped) return;
        setStatus('reconnecting');
        scheduleReconnect();
      };
    }

    // ── Online / offline / visibility ─────────────────────────────────────

    function handleOnline() {
      if (!stopped && !es) {
        clearTimeout(backoffTimer);
        connect();
      }
    }

    function handleOffline() {
      clearTimeout(backoffTimer);
      if (es) { es.close(); es = null; }
      if (!stopped) setStatus('reconnecting');
    }

    function handleVisibilityChange() {
      if (document.visibilityState === 'visible' && !es && !stopped) {
        clearTimeout(backoffTimer);
        connect();
      }
    }

    window.addEventListener('online',  handleOnline);
    window.addEventListener('offline', handleOffline);
    document.addEventListener('visibilitychange', handleVisibilityChange);

    // Kick off initial connection
    connect();

    // ── Cleanup ────────────────────────────────────────────────────────────
    return () => {
      stopped = true;
      clearTimeout(backoffTimer);
      clearTimeout(stalenessTimer);
      clearTimeout(debounceTimer);
      if (es) { es.close(); es = null; }
      window.removeEventListener('online',  handleOnline);
      window.removeEventListener('offline', handleOffline);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [refreshTick]); // re-run on manual refresh only

  return { status, lastEventAt, refresh };
}
