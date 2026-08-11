/**
 * SSE client with single-use stream ticket authentication.
 *
 * Security contract:
 * - The access token MUST NEVER appear in a URL or query parameter.
 * - Authentication uses a single-use, IP-bound stream ticket obtained via
 *   POST /api/v1/auth/stream-ticket (bearer-authenticated).
 * - Each reconnect requests a FRESH ticket — expired/consumed tickets are
 *   never reused.
 *
 * Reconnect strategy: capped jittered exponential backoff.
 * The client never exceeds the authenticated rate-limit budget.
 */

import { apiFetch } from './http.js';

const BASE_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS  = 30_000;

/**
 * @typedef {{
 *   onMessage: (event: { type: string, data: string }) => void,
 *   onError?:  (err: Error) => void,
 *   onOpen?:   () => void,
 * }} SseClientOptions
 */

/**
 * Creates an SSE client that manages the full ticket → connect → reconnect lifecycle.
 *
 * @param {string} streamPath  Path under /api/v1, e.g. '/streams/work-orders'
 * @param {SseClientOptions} options
 * @returns {{ start: () => void, stop: () => void, isActive: () => boolean }}
 */
export function createSseClient(streamPath, { onMessage, onError, onOpen } = {}) {
  let _es       = null;
  let _attempt  = 0;
  let _stopped  = false;
  let _timeout  = null;

  async function _connect() {
    if (_stopped) return;

    let ticket;
    try {
      const resp = await apiFetch('/auth/stream-ticket', { method: 'POST' });
      ticket = resp?.ticket;
      if (!ticket || typeof ticket !== 'string') {
        throw new Error('stream-ticket response missing ticket field');
      }
    } catch (err) {
      _scheduleReconnect(err);
      return;
    }

    // Open EventSource with ticket as query parameter.
    // The raw access token is NEVER placed in a URL.
    const url = `/api/v1${streamPath}?ticket=${encodeURIComponent(ticket)}`;

    let es;
    try {
      es = new EventSource(url);
    } catch (err) {
      _scheduleReconnect(err);
      return;
    }
    _es = es;

    es.onopen = () => {
      _attempt = 0; // reset backoff on successful connection
      onOpen?.();
    };

    es.onmessage = (event) => {
      try {
        onMessage({ type: event.type, data: event.data });
      } catch (_) {}
    };

    // Custom named events (e.g. 'at-risk', 'breach', 'state-change')
    const NAMED_EVENTS = ['at-risk', 'breach', 'state-change', 'parts-consumed', 'parts-returned'];
    for (const name of NAMED_EVENTS) {
      es.addEventListener(name, (event) => {
        try {
          onMessage({ type: name, data: event.data });
        } catch (_) {}
      });
    }

    es.onerror = () => {
      es.close();
      _es = null;
      _scheduleReconnect(new Error('EventSource connection error'));
    };
  }

  function _scheduleReconnect(cause) {
    if (_stopped) return;
    const delay = _backoff(_attempt);
    _attempt++;
    if (onError && cause instanceof Error) onError(cause);
    _timeout = setTimeout(_connect, delay);
  }

  function _backoff(attempt) {
    const base = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * Math.pow(2, attempt));
    return Math.round(base * (0.5 + Math.random() * 0.5));
  }

  return {
    start() {
      _stopped = false;
      _attempt = 0;
      _connect();
    },

    stop() {
      _stopped = true;
      clearTimeout(_timeout);
      if (_es) {
        _es.close();
        _es = null;
      }
    },

    isActive() {
      return !_stopped && _es !== null;
    },
  };
}
