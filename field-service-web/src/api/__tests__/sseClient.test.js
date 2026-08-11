import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { createSseClient } from '../sseClient.js';
import { tokenStore } from '../tokenStore.js';

// ---- Fake EventSource --------------------------------------------------

class FakeEventSource {
  constructor(url) {
    FakeEventSource.lastUrl = url;
    FakeEventSource.instances.push(this);
    this.onopen = null;
    this.onerror = null;
    this.onmessage = null;
    this._listeners = {};
    this.readyState = 0;
  }

  addEventListener(type, handler) {
    this._listeners[type] = handler;
  }

  close() {
    this.readyState = 2;
    FakeEventSource.closedCount++;
  }

  // Test helper: trigger open
  triggerOpen() { this.onopen?.(); }
  // Test helper: trigger message
  triggerMessage(data) { this.onmessage?.({ type: 'message', data }); }
  // Test helper: trigger named event
  triggerNamed(type, data) { this._listeners[type]?.({ type, data }); }
  // Test helper: trigger error
  triggerError() { this.onerror?.(); }

  static reset() {
    FakeEventSource.lastUrl = null;
    FakeEventSource.instances = [];
    FakeEventSource.closedCount = 0;
  }
}
FakeEventSource.reset();

// ---- Test setup --------------------------------------------------------

beforeEach(() => {
  FakeEventSource.reset();
  globalThis.EventSource = FakeEventSource;
  tokenStore.reset();

  // Mock fetch so stream-ticket requests succeed
  globalThis.fetch = vi.fn(async (url) => {
    if (url.includes('/auth/stream-ticket')) {
      return new Response(
        JSON.stringify({ ticket: 'ticket-' + Math.random().toString(36).slice(2), expiresIn: 60 }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }
    return new Response(JSON.stringify({}), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
});

afterEach(() => {
  delete globalThis.EventSource;
});

// ---- Tests -------------------------------------------------------------

describe('createSseClient', () => {
  it('requests a stream ticket before opening EventSource', async () => {
    const client = createSseClient('/streams/work-orders', { onMessage: () => {} });
    client.start();
    await vi.waitFor(() => FakeEventSource.instances.length > 0);

    const fetchCalls = globalThis.fetch.mock.calls.map(c => c[0]);
    expect(fetchCalls.some(u => u.includes('/auth/stream-ticket'))).toBe(true);
  });

  it('never puts the access token in the EventSource URL', async () => {
    tokenStore.set('super-secret-bearer-token');

    const client = createSseClient('/streams/work-orders', { onMessage: () => {} });
    client.start();
    await vi.waitFor(() => FakeEventSource.instances.length > 0);

    expect(FakeEventSource.lastUrl).not.toContain('super-secret-bearer-token');
    client.stop();
  });

  it('uses ticket as query parameter in the EventSource URL', async () => {
    const client = createSseClient('/streams/work-orders', { onMessage: () => {} });
    client.start();
    await vi.waitFor(() => FakeEventSource.instances.length > 0);

    expect(FakeEventSource.lastUrl).toContain('ticket=');
    client.stop();
  });

  it('passes received messages to onMessage', async () => {
    const received = [];
    const client = createSseClient('/streams/work-orders', {
      onMessage: (e) => received.push(e),
    });
    client.start();
    await vi.waitFor(() => FakeEventSource.instances.length > 0);

    const es = FakeEventSource.instances[0];
    es.triggerMessage('{"eventType":"at-risk"}');

    expect(received).toHaveLength(1);
    expect(received[0].data).toBe('{"eventType":"at-risk"}');
    client.stop();
  });

  it('requests a fresh ticket on reconnect (consumed ticket not reused)', async () => {
    const tickets = new Set();
    globalThis.fetch = vi.fn(async (url) => {
      if (url.includes('/auth/stream-ticket')) {
        const ticket = 'ticket-' + Math.random().toString(36).slice(2);
        tickets.add(ticket);
        return new Response(JSON.stringify({ ticket, expiresIn: 60 }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    });

    const client = createSseClient('/streams/work-orders', { onMessage: () => {} });
    client.start();
    await vi.waitFor(() => FakeEventSource.instances.length >= 1);

    const firstTicketUrl = FakeEventSource.lastUrl;

    // Trigger error to force reconnect
    const es1 = FakeEventSource.instances[0];
    es1.triggerError();

    // Use fake timers to advance past backoff
    vi.useFakeTimers();
    vi.advanceTimersByTime(5000);
    vi.useRealTimers();

    await vi.waitFor(() => tickets.size >= 2, { timeout: 5000 });

    // Each reconnect must use a different ticket
    expect(tickets.size).toBeGreaterThanOrEqual(2);
    client.stop();
  });

  it('stops cleanly and closes EventSource', async () => {
    const client = createSseClient('/streams/work-orders', { onMessage: () => {} });
    client.start();
    await vi.waitFor(() => FakeEventSource.instances.length > 0);

    client.stop();
    expect(FakeEventSource.closedCount).toBeGreaterThan(0);
    expect(client.isActive()).toBe(false);
  });
});
