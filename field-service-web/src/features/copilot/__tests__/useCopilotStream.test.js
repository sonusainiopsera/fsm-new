/**
 * useCopilotStream — unit tests for the explicit state machine and SSE lifecycle.
 *
 * Uses a MockEventSource that exposes dispatchNamedEvent() for scripted delivery,
 * installHandlers() for the fetch intercept, and mockRespond() for per-test overrides.
 *
 * Covers:
 *   - IDLE → STREAMING → COMPLETE (happy path)
 *   - Ordered chunk accumulation
 *   - Out-of-order chunkIndex: deterministic output regardless of arrival order
 *   - Immediate refusal: no_grounded_basis → REFUSED
 *   - Degraded after partial content → PARTIAL
 *   - Degraded with no prior content → DEGRADED
 *   - EventSource onerror with no content → DEGRADED
 *   - 429 on ticket fetch → CAPPED with retryAfter
 *   - 503 on ticket fetch → DEGRADED
 *   - Cleanup on unmount: EventSource.close() called
 *   - Double-start guard: second call while STREAMING is ignored
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
} from '../../../mocks/handlers/index.js';
import {
  addCopilotHandlers,
  copilotCappedFixture,
  copilotUnavailableFixture,
  MOCK_COPILOT_TICKET,
} from '../../../mocks/handlers/copilotHandlers.js';
import sseScripts from '../../../mocks/fixtures/copilot-sse-scripts.json';

// ─── Mock EventSource ─────────────────────────────────────────────────────────

let lastCreatedEs = null;

class MockEventSource {
  constructor(url) {
    this.url         = url;
    this.readyState  = 0; // CONNECTING
    this.OPEN        = 1;
    this.CLOSED      = 2;
    this.onerror     = null;
    this._listeners  = {};
    lastCreatedEs    = this;
  }

  addEventListener(type, fn) {
    if (!this._listeners[type]) this._listeners[type] = [];
    this._listeners[type].push(fn);
  }

  /** Dispatch a named SSE event with JSON-serialised data. */
  dispatchNamedEvent(type, data = {}) {
    const fns = this._listeners[type] ?? [];
    const evt = { type, data: JSON.stringify(data) };
    fns.forEach((fn) => fn(evt));
  }

  triggerOnerror() {
    this.onerror?.({ type: 'error' });
  }

  close() {
    this.readyState = 2;
  }
}

// ─── Mock requestAnimationFrame / cancelAnimationFrame ───────────────────────

// jsdom doesn't implement rAF; provide a synchronous flush.
const rafCallbacks = [];
function mockRaf(fn) {
  const id = rafCallbacks.push(fn);
  return id;
}
function mockCaf(id) {
  rafCallbacks[id - 1] = null;
}
function flushRaf() {
  const toRun = [...rafCallbacks];
  rafCallbacks.length = 0;
  toRun.forEach((fn) => fn && fn(performance.now()));
}

// ─── Setup / teardown ────────────────────────────────────────────────────────

beforeEach(() => {
  globalThis.EventSource           = MockEventSource;
  globalThis.requestAnimationFrame = mockRaf;
  globalThis.cancelAnimationFrame  = mockCaf;
  installHandlers();
  addCopilotHandlers();
  lastCreatedEs = null;
});

afterEach(() => {
  resetHandlers();
  uninstallHandlers();
  delete globalThis.EventSource;
  delete globalThis.requestAnimationFrame;
  delete globalThis.cancelAnimationFrame;
  vi.restoreAllMocks();
});

// ─── Helper ──────────────────────────────────────────────────────────────────

async function importHook() {
  const mod = await import('../useCopilotStream.js');
  return mod.useCopilotStream;
}

async function startStream(result, workOrderId = 'wo-001', question = 'What failed last time?') {
  await act(async () => { result.current.start(workOrderId, question); });
  // Let the ticket fetch resolve
  await act(async () => { await Promise.resolve(); });
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('useCopilotStream', () => {
  it('starts in IDLE state', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());
    expect(result.current.state).toBe('IDLE');
  });

  it('transitions to STREAMING after start()', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    expect(result.current.state).toBe('STREAMING');
  });

  it('opens EventSource with ticket as query param', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    expect(lastCreatedEs).not.toBeNull();
    expect(lastCreatedEs.url).toContain(MOCK_COPILOT_TICKET);
  });

  it('accumulates ordered chunks and transitions to COMPLETE on complete event', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    const es = lastCreatedEs;

    // Dispatch token events from the happy path script
    const script = sseScripts.happyPath.events;
    act(() => {
      for (const evt of script) {
        if (evt.type !== 'complete') {
          es.dispatchNamedEvent(evt.type, evt.data);
        }
      }
    });
    act(() => { flushRaf(); }); // flush rAF batch

    const completeEvt = script.find((e) => e.type === 'complete');
    act(() => { es.dispatchNamedEvent('complete', completeEvt.data); });

    expect(result.current.state).toBe('COMPLETE');
    expect(result.current.answer).toBe(
      'The compressor on this unit has required bypass valve replacement twice in the past 12 months.',
    );
    expect(result.current.basis?.assetName).toBe('Carrier 30XW — Chiller Unit B');
    expect(result.current.interactionId).toBe('interaction-001');
  });

  it('renders out-of-order chunks in chunkIndex order (deterministic)', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    const es = lastCreatedEs;
    const script = sseScripts.outOfOrderChunks.events;

    act(() => {
      for (const evt of script) {
        if (evt.type !== 'complete') es.dispatchNamedEvent(evt.type, evt.data);
      }
    });
    act(() => { flushRaf(); });

    const completeEvt = script.find((e) => e.type === 'complete');
    act(() => { es.dispatchNamedEvent('complete', completeEvt.data); });

    expect(result.current.state).toBe('COMPLETE');
    expect(result.current.answer).toBe(sseScripts.outOfOrderChunks.expectedAnswer);
  });

  it('transitions to REFUSED on no_grounded_basis event', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    act(() => { lastCreatedEs.dispatchNamedEvent('no_grounded_basis', {}); });

    expect(result.current.state).toBe('REFUSED');
    expect(result.current.answer).toBe('');
  });

  it('transitions to PARTIAL when degraded event arrives after partial content', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    const es = lastCreatedEs;
    const script = sseScripts.degradedAfterPartial.events;

    act(() => {
      for (const evt of script) {
        if (evt.type !== 'degraded') es.dispatchNamedEvent(evt.type, evt.data);
      }
    });
    act(() => { flushRaf(); });
    act(() => { es.dispatchNamedEvent('degraded', {}); });

    expect(result.current.state).toBe('PARTIAL');
    expect(result.current.answer).toContain('Initial check');
  });

  it('transitions to DEGRADED when degraded event arrives with no prior content', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    act(() => { lastCreatedEs.dispatchNamedEvent('degraded', {}); });

    expect(result.current.state).toBe('DEGRADED');
    expect(result.current.answer).toBe('');
  });

  it('transitions to DEGRADED on EventSource onerror with no content', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    act(() => { lastCreatedEs.triggerOnerror(); });

    expect(result.current.state).toBe('DEGRADED');
  });

  it('transitions to CAPPED with retryAfter on 429 ticket response', async () => {
    mockRespond('POST', '/api/v1/copilot/stream-ticket', copilotCappedFixture(300));
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);

    expect(result.current.state).toBe('CAPPED');
    expect(result.current.retryAfter).toBe(300);
    expect(lastCreatedEs).toBeNull(); // no EventSource opened
  });

  it('transitions to DEGRADED on 503 ticket response', async () => {
    mockRespond('POST', '/api/v1/copilot/stream-ticket', copilotUnavailableFixture());
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);

    expect(result.current.state).toBe('DEGRADED');
    expect(lastCreatedEs).toBeNull();
  });

  it('strips control characters from chunk text', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    const es = lastCreatedEs;

    act(() => {
      // \x01 is a control character and should be stripped; \n (0x0A) must be preserved
      es.dispatchNamedEvent('token', { chunkIndex: 0, text: 'Clean\x01 text\nwith newline' });
    });
    act(() => { flushRaf(); });
    act(() => { es.dispatchNamedEvent('complete', { interactionId: 'i-001', basis: null }); });

    expect(result.current.answer).toBe('Clean text\nwith newline');
  });

  it('does not double-start when already STREAMING', async () => {
    const useCopilotStream = await importHook();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    const callsBefore = fetchSpy.mock.calls.length;

    // Second start while streaming — should be a no-op
    await act(async () => { result.current.start('wo-001', 'second question'); });
    await act(async () => { await Promise.resolve(); });

    expect(fetchSpy.mock.calls.length).toBe(callsBefore);
    expect(result.current.state).toBe('STREAMING');
  });

  it('reset() returns to IDLE and clears answer', async () => {
    const useCopilotStream = await importHook();
    const { result } = renderHook(() => useCopilotStream());

    await startStream(result);
    act(() => {
      lastCreatedEs.dispatchNamedEvent('token', { chunkIndex: 0, text: 'hello' });
      flushRaf();
    });
    expect(result.current.answer).toBe('hello');

    act(() => { result.current.reset(); });

    expect(result.current.state).toBe('IDLE');
    expect(result.current.answer).toBe('');
    expect(result.current.basis).toBeNull();
    expect(result.current.interactionId).toBeNull();
  });

  it('closes EventSource on unmount', async () => {
    const useCopilotStream = await importHook();
    const { result, unmount } = renderHook(() => useCopilotStream());

    await startStream(result);
    const es = lastCreatedEs;

    unmount();
    expect(es.readyState).toBe(2); // CLOSED
  });
});
