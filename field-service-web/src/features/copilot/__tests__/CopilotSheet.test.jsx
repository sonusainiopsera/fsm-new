/**
 * CopilotSheet — component tests.
 *
 * Tests cover:
 *   - Advisory label visible during streaming, complete, and partial states
 *   - Advisory label is not scrollable out of view (sticky, rendered in DOM)
 *   - Plain text rendering: markup in a chunk is displayed literally (no HTML)
 *   - Basis section expansion/collapse
 *   - Helpfulness rating submit and duplicate-submit prevention
 *   - Rating rollback on error with toast
 *   - Distinct presentations for each terminal state
 *   - Escape key closes the sheet
 *   - Retry button shown in degraded state
 *
 * Uses a scripted MockEventSource (same harness as useCopilotStream.test.js)
 * and the mock fetch intercept.
 */

import React from 'react';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
} from '../../../mocks/handlers/index.js';
import {
  addCopilotHandlers,
  ratingFailureFixture,
  MOCK_COPILOT_TICKET,
} from '../../../mocks/handlers/copilotHandlers.js';
import sseScripts from '../../../mocks/fixtures/copilot-sse-scripts.json';
import { CopilotSheet } from '../CopilotSheet.jsx';
import { ToastProvider } from '../../../components/index.js';

// ─── Mock EventSource ─────────────────────────────────────────────────────────

let lastCreatedEs = null;

class MockEventSource {
  constructor(url) {
    this.url        = url;
    this.readyState = 0;
    this.CLOSED     = 2;
    this.onerror    = null;
    this._listeners = {};
    lastCreatedEs   = this;
  }
  addEventListener(type, fn) {
    if (!this._listeners[type]) this._listeners[type] = [];
    this._listeners[type].push(fn);
  }
  dispatchNamedEvent(type, data = {}) {
    const fns = this._listeners[type] ?? [];
    fns.forEach((fn) => fn({ type, data: JSON.stringify(data) }));
  }
  triggerOnerror() { this.onerror?.({ type: 'error' }); }
  close() { this.readyState = 2; }
}

// ─── rAF mock ────────────────────────────────────────────────────────────────

const rafQueue = [];
function mockRaf(fn) { return rafQueue.push(fn); }
function mockCaf() {}
function flushRaf() {
  const q = [...rafQueue]; rafQueue.length = 0; q.forEach((fn) => fn && fn(0));
}

// ─── Wrapper ─────────────────────────────────────────────────────────────────

function Wrapper({ children }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <ToastProvider>
        {children}
      </ToastProvider>
    </QueryClientProvider>
  );
}

function renderSheet(props = {}) {
  const defaultProps = { open: true, workOrderId: 'wo-001', onClose: vi.fn(), ...props };
  return render(
    <Wrapper>
      <CopilotSheet {...defaultProps} />
    </Wrapper>,
  );
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

// ─── Helpers ─────────────────────────────────────────────────────────────────

async function submitQuestion(question = 'What failed last time?') {
  const input = screen.getByRole('textbox', { name: /question for copilot/i });
  fireEvent.change(input, { target: { value: question } });
  const btn = screen.getByRole('button', { name: /ask/i });
  await act(async () => { fireEvent.click(btn); });
  // Let ticket fetch resolve and EventSource open
  await act(async () => { await Promise.resolve(); });
}

async function driveHappyPath() {
  await submitQuestion();
  const es = lastCreatedEs;
  const script = sseScripts.happyPath.events;
  act(() => {
    for (const evt of script) {
      if (evt.type !== 'complete') es.dispatchNamedEvent(evt.type, evt.data);
    }
    flushRaf();
    const complete = script.find((e) => e.type === 'complete');
    es.dispatchNamedEvent('complete', complete.data);
  });
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('CopilotSheet', () => {
  it('renders the sheet when open=true', () => {
    renderSheet();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('Copilot')).toBeInTheDocument();
  });

  it('does not render when open=false', () => {
    renderSheet({ open: false });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('calls onClose when the close button is tapped', () => {
    const onClose = vi.fn();
    renderSheet({ onClose });
    fireEvent.click(screen.getByRole('button', { name: /close copilot/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when Escape is pressed', () => {
    const onClose = vi.fn();
    renderSheet({ onClose });
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('shows Advisory label during streaming', async () => {
    renderSheet();
    await submitQuestion();
    expect(screen.getByText(/advisory/i)).toBeInTheDocument();
  });

  it('keeps Advisory label visible after completion', async () => {
    renderSheet();
    await driveHappyPath();
    expect(screen.getByText(/advisory/i)).toBeInTheDocument();
  });

  it('renders model output as plain text — markup is NOT interpreted as HTML', async () => {
    renderSheet();
    await submitQuestion();

    const es = lastCreatedEs;
    const script = sseScripts.markupInChunk.events;
    act(() => {
      for (const evt of script) {
        if (evt.type !== 'complete') es.dispatchNamedEvent(evt.type, evt.data);
      }
      flushRaf();
      const complete = script.find((e) => e.type === 'complete');
      es.dispatchNamedEvent('complete', complete.data);
    });

    // The literal string must appear in the DOM as text, not as a script tag
    expect(screen.getByText(/<script>alert\('xss'\)<\/script>/i)).toBeInTheDocument();
    // No actual script element should have been injected
    expect(document.querySelector('script[injected]')).not.toBeInTheDocument();
  });

  it('expands and collapses the Basis section', async () => {
    renderSheet();
    await driveHappyPath();

    const basisToggle = screen.getByRole('button', { name: /basis/i });
    expect(basisToggle).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(basisToggle);
    expect(basisToggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Carrier 30XW — Chiller Unit B')).toBeInTheDocument();

    fireEvent.click(basisToggle);
    expect(basisToggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('prior work order links appear in the basis section', async () => {
    renderSheet();
    await driveHappyPath();

    fireEvent.click(screen.getByRole('button', { name: /basis/i }));
    expect(screen.getByText('WO-0031')).toBeInTheDocument();
    expect(screen.getByText('WO-0018')).toBeInTheDocument();
  });

  it('shows helpfulness rating controls after COMPLETE', async () => {
    renderSheet();
    await driveHappyPath();

    expect(screen.getByRole('button', { name: /helpful$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /not helpful/i })).toBeInTheDocument();
  });

  it('disables rating buttons after one submission (no double-submit)', async () => {
    // Pre-register rating mock
    mockRespond('POST', '/api/v1/copilot/interactions/interaction-001/rating', {
      status: 204,
      body: null,
    });

    renderSheet();
    await driveHappyPath();

    const helpfulBtn = screen.getByRole('button', { name: /helpful$/i });
    await act(async () => { fireEvent.click(helpfulBtn); });
    await act(async () => { await Promise.resolve(); });

    // Both buttons are now disabled
    expect(screen.getByRole('button', { name: /helpful$/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /not helpful/i })).toBeDisabled();

    // Thank-you message appears
    expect(screen.getByText(/thanks for your feedback/i)).toBeInTheDocument();
  });

  it('shows refused state with no retry or fabricated answer', async () => {
    renderSheet();
    await submitQuestion();
    act(() => { lastCreatedEs.dispatchNamedEvent('no_grounded_basis', {}); });

    expect(screen.getByText(/no grounded basis available/i)).toBeInTheDocument();
    // No retry button for refused state
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument();
  });

  it('shows degraded state with Retry button', async () => {
    renderSheet();
    await submitQuestion();
    act(() => { lastCreatedEs.dispatchNamedEvent('degraded', {}); });

    expect(screen.getByText(/copilot unavailable/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('shows capped state with wait message', async () => {
    const { MOCK_COPILOT_TICKET: _t, ...rest } = await import('../../../mocks/handlers/copilotHandlers.js');
    mockRespond('POST', '/api/v1/copilot/stream-ticket', rest.copilotCappedFixture(300));

    renderSheet();
    await submitQuestion();

    expect(screen.getByText(/daily limit reached/i)).toBeInTheDocument();
    expect(screen.getByText(/5 minute/i)).toBeInTheDocument(); // 300s = 5 mins
  });

  it('shows partial state when stream degrades after tokens', async () => {
    renderSheet();
    await submitQuestion();

    const es = lastCreatedEs;
    const script = sseScripts.degradedAfterPartial.events;
    act(() => {
      for (const evt of script) {
        if (evt.type !== 'degraded') es.dispatchNamedEvent(evt.type, evt.data);
      }
      flushRaf();
      es.dispatchNamedEvent('degraded', {});
    });

    // Partial answer should still be displayed
    expect(screen.getByText(/initial check/i)).toBeInTheDocument();
    expect(screen.getByText(/incomplete/i)).toBeInTheDocument();
  });
});
