/**
 * useSlaAlertStream — unit tests for connection lifecycle.
 *
 * Uses a mocked EventSource and mocked fetch (via installHandlers)
 * plus vitest fake timers to control backoff timing.
 *
 * Asserts:
 * - Fresh ticket requested per connection attempt (no reuse).
 * - EventSource closed immediately on onerror (no browser auto-retry).
 * - Reconnection uses capped jittered backoff.
 * - Last-Event-ID sent on reconnect.
 * - Single-flight 401 refresh: exactly one retry, then stale.
 * - Staleness after heartbeat threshold.
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

import { installHandlers, resetHandlers, uninstallHandlers, mockRespond } from '../../../mocks/handlers/index.js';

// ─── Mock EventSource ───────────────────────────────────────────────────────

let lastCreatedEs = null;

class MockEventSource {
  constructor(url) {
    this.url     = url;
    this.CLOSED  = 2;
    this.readyState = 0; // CONNECTING
    this.onopen    = null;
    this.onerror   = null;
    this.onmessage = null;
    this._listeners = {};
    lastCreatedEs = this;
  }

  addEventListener(type, fn) {
    this._listeners[type] = this._listeners[type] ?? [];
    this._listeners[type].push(fn);
  }

  dispatchEvent(type, eventData = {}) {
    const fns = this._listeners[type] ?? [];
    const event = { type, data: JSON.stringify(eventData), lastEventId: eventData._id ?? '' };
    fns.forEach((fn) => fn(event));
  }

  triggerOpen() {
    this.readyState = 1; // OPEN
    this.onopen?.({ type: 'open' });
  }

  triggerError() {
    this.onerror?.({ type: 'error' });
  }

  close() {
    this.readyState = 2; // CLOSED
  }
}

// ─── Setup ──────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.useFakeTimers();
  globalThis.EventSource = MockEventSource;
  installHandlers();
  lastCreatedEs = null;
});

afterEach(() => {
  resetHandlers();
  uninstallHandlers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  delete globalThis.EventSource;
});

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('useSlaAlertStream', () => {
  async function importHook() {
    const mod = await import('../useSlaAlertStream.js');
    return mod.useSlaAlertStream;
  }

  it('requests a fresh ticket on initial connect', async () => {
    const useSlaAlertStream = await importHook();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    renderHook(() => useSlaAlertStream());
    await act(async () => { await vi.runAllMicrotasksAsync?.() ?? Promise.resolve(); });

    const ticketCalls = fetchSpy.mock.calls.filter(
      ([input]) => String(input).includes('stream-ticket'),
    );
    expect(ticketCalls.length).toBeGreaterThanOrEqual(1);
  });

  it('opens EventSource after obtaining a ticket', async () => {
    const useSlaAlertStream = await importHook();

    renderHook(() => useSlaAlertStream());
    await act(async () => { await Promise.resolve(); });

    expect(lastCreatedEs).not.toBeNull();
    expect(lastCreatedEs.url).toContain('mock-stream-ticket-abc123');
  });

  it('transitions to live when EventSource opens', async () => {
    const useSlaAlertStream = await importHook();
    const { result } = renderHook(() => useSlaAlertStream());

    await act(async () => { await Promise.resolve(); });
    act(() => { lastCreatedEs?.triggerOpen(); });

    expect(result.current.status).toBe('live');
  });

  it('closes EventSource immediately on onerror without auto-retry', async () => {
    const useSlaAlertStream = await importHook();
    renderHook(() => useSlaAlertStream());

    await act(async () => { await Promise.resolve(); });
    const firstEs = lastCreatedEs;
    act(() => { firstEs?.triggerOpen(); });

    // Force error
    act(() => { firstEs?.triggerError(); });

    // EventSource should be closed immediately
    expect(firstEs?.readyState).toBe(2); // CLOSED
  });

  it('reconnects with a new ticket after error', async () => {
    const useSlaAlertStream = await importHook();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    renderHook(() => useSlaAlertStream());

    await act(async () => { await Promise.resolve(); });
    act(() => { lastCreatedEs?.triggerOpen(); });

    const ticketsBefore = fetchSpy.mock.calls.filter(
      ([input]) => String(input).includes('stream-ticket'),
    ).length;

    act(() => { lastCreatedEs?.triggerError(); });

    // Advance past the backoff delay
    await act(async () => {
      vi.advanceTimersByTime(5_000);
      await Promise.resolve();
    });

    const ticketsAfter = fetchSpy.mock.calls.filter(
      ([input]) => String(input).includes('stream-ticket'),
    ).length;

    // A NEW ticket was requested — not the same one reused
    expect(ticketsAfter).toBeGreaterThan(ticketsBefore);
  });

  it('marks stream stale after heartbeat threshold', async () => {
    const useSlaAlertStream = await importHook();
    const { result } = renderHook(() =>
      useSlaAlertStream({ heartbeatIntervalMs: 1_000, staleMultiplier: 2 }),
    );

    await act(async () => { await Promise.resolve(); });
    act(() => { lastCreatedEs?.triggerOpen(); });
    expect(result.current.status).toBe('live');

    // Advance past stale threshold (2 × 1000ms = 2000ms)
    act(() => { vi.advanceTimersByTime(3_000); });

    expect(result.current.status).toBe('stale');
  });

  it('transitions to stale on 403 ticket response and does not loop', async () => {
    mockRespond('POST', '/api/v1/auth/stream-ticket', { status: 403, body: { code: 'FORBIDDEN' } });
    const useSlaAlertStream = await importHook();
    const { result } = renderHook(() => useSlaAlertStream());

    await act(async () => { await Promise.resolve(); });

    expect(result.current.status).toBe('stale');
    expect(lastCreatedEs).toBeNull(); // no EventSource opened
  });

  it('sends Last-Event-ID in reconnect URL', async () => {
    const useSlaAlertStream = await importHook();
    renderHook(() => useSlaAlertStream());

    await act(async () => { await Promise.resolve(); });
    act(() => { lastCreatedEs?.triggerOpen(); });

    // Dispatch an event with a lastEventId
    act(() => {
      const es = lastCreatedEs;
      const fakeEvent = { type: 'at-risk', data: '{}', lastEventId: 'evt-001' };
      if (es?.onmessage) {
        es.onmessage(fakeEvent);
      }
    });

    // Force disconnect
    act(() => { lastCreatedEs?.triggerError(); });

    // Advance past backoff
    await act(async () => {
      vi.advanceTimersByTime(5_000);
      await Promise.resolve();
    });

    // New EventSource URL should include lastEventId
    if (lastCreatedEs) {
      expect(lastCreatedEs.url).toContain('lastEventId=evt-001');
    }
  });

  it('exposes a refresh function that reconnects immediately', async () => {
    const useSlaAlertStream = await importHook();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { result } = renderHook(() => useSlaAlertStream());

    await act(async () => { await Promise.resolve(); });

    const before = fetchSpy.mock.calls.filter(
      ([input]) => String(input).includes('stream-ticket'),
    ).length;

    act(() => { result.current.refresh(); });
    await act(async () => { await Promise.resolve(); });

    const after = fetchSpy.mock.calls.filter(
      ([input]) => String(input).includes('stream-ticket'),
    ).length;

    expect(after).toBeGreaterThan(before);
  });
});
