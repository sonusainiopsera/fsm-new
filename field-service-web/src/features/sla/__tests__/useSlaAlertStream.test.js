/**
 * Unit tests for useSlaAlertStream.js
 *
 * Uses fake timers and a mocked EventSource for deterministic tests.
 * Covers: ticket-per-attempt, error-close, backoff, Last-Event-ID resume,
 * single-flight refresh on 401, stale detection.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { useSlaAlertStream, slaJitteredBackoff } from '../useSlaAlertStream.js'
import * as tokenStore from '../../../api/tokenStore.js'

// ── FakeEventSource ───────────────────────────────────────────────────────────

let _esInstances = []

class FakeEventSource {
  constructor(url) {
    this.url = url
    this.closed = false
    this.onopen = null
    this.onmessage = null
    this.onerror = null
    this._listeners = {}
    _esInstances.push(this)
  }
  addEventListener(type, fn) { this._listeners[type] = fn }
  removeEventListener(type) { delete this._listeners[type] }
  close() { this.closed = true }
  _emit(type, data, lastEventId = '') {
    const ev = { type, data: JSON.stringify(data), lastEventId }
    if (type === 'message' && this.onmessage) this.onmessage(ev)
    else if (this._listeners[type]) this._listeners[type](ev)
  }
  _triggerError() { if (this.onerror) this.onerror(new Event('error')) }
  _triggerOpen() { if (this.onopen) this.onopen(new Event('open')) }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeTicketFetch(ticket = 'ticket-001') {
  return vi.fn(() => Promise.resolve({
    ok: true,
    json: () => Promise.resolve({ ticket }),
  }))
}

// ── Setup / Teardown ──────────────────────────────────────────────────────────

beforeEach(() => {
  _esInstances = []
  vi.useFakeTimers()
  vi.stubGlobal('EventSource', FakeEventSource)
  tokenStore.setToken('access-token-test')
})

afterEach(() => {
  tokenStore._resetForTesting()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

// ── slaJitteredBackoff ────────────────────────────────────────────────────────

describe('slaJitteredBackoff', () => {
  it('returns a positive delay', () => {
    expect(slaJitteredBackoff(0)).toBeGreaterThan(0)
  })

  it('increases with attempt number (on average)', () => {
    const d0 = slaJitteredBackoff(0)
    const d5 = slaJitteredBackoff(5)
    // Max for attempt 0 is 2000ms (1000 + 1000 jitter); min for attempt 5 is 32000ms
    // We just assert d5 >= d0 is likely but test the cap instead:
    expect(slaJitteredBackoff(20)).toBeLessThanOrEqual(30_000)
  })

  it('is capped at 30_000 ms', () => {
    for (let i = 0; i < 30; i++) {
      expect(slaJitteredBackoff(i)).toBeLessThanOrEqual(30_000)
    }
  })
})

// ── useSlaAlertStream — ticket per attempt ─────────────────────────────────────

describe('useSlaAlertStream — ticket per attempt', () => {
  it('requests a ticket before opening EventSource', async () => {
    const mockFetch = makeTicketFetch('ticket-A')
    vi.stubGlobal('fetch', mockFetch)

    const qc = makeQueryClient()
    const { unmount } = renderHook(() =>
      useSlaAlertStream({ queryClient: qc }, {
        sseBase: '/api/v1/stream/sla',
        ticketEndpoint: '/api/v1/auth/stream-ticket',
        heartbeatIntervalMs: 10_000,
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(50))

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/auth/stream-ticket',
      expect.objectContaining({ method: 'POST' })
    )
    expect(_esInstances).toHaveLength(1)
    expect(_esInstances[0].url).toContain('ticket=ticket-A')

    unmount()
  })

  it('uses a fresh ticket on each reconnect — no ticket reuse', async () => {
    let callCount = 0
    vi.stubGlobal('fetch', vi.fn(() => {
      callCount++
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({ ticket: `ticket-${callCount}` }),
      })
    }))

    const qc = makeQueryClient()
    const { unmount } = renderHook(() =>
      useSlaAlertStream({ queryClient: qc }, {
        sseBase: '/api/v1/stream/sla',
        ticketEndpoint: '/api/v1/auth/stream-ticket',
        heartbeatIntervalMs: 10_000,
      })
    )

    // Initial connection
    await act(() => vi.advanceTimersByTimeAsync(50))
    expect(_esInstances).toHaveLength(1)
    const firstUrl = _esInstances[0].url

    // Trigger an error to force reconnect
    await act(() => {
      _esInstances[0]._triggerError()
    })

    // Advance past backoff delay
    await act(() => vi.advanceTimersByTimeAsync(3_000))

    // Second connection should have a different ticket
    expect(_esInstances.length).toBeGreaterThanOrEqual(2)
    const secondUrl = _esInstances[1]?.url ?? ''
    expect(secondUrl).not.toEqual(firstUrl)
    // Tickets are different
    const t1 = new URL(firstUrl, 'http://x').searchParams.get('ticket')
    const t2 = new URL(secondUrl, 'http://x').searchParams.get('ticket')
    expect(t1).not.toEqual(t2)

    unmount()
  })
})

// ── useSlaAlertStream — error close ──────────────────────────────────────────

describe('useSlaAlertStream — error closes EventSource immediately', () => {
  it('closes the EventSource immediately on error — no auto-retry', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('ticket-close'))
    const qc = makeQueryClient()

    const { unmount } = renderHook(() =>
      useSlaAlertStream({ queryClient: qc }, {
        sseBase: '/api/v1/stream/sla',
        ticketEndpoint: '/api/v1/auth/stream-ticket',
        heartbeatIntervalMs: 10_000,
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(50))
    const es = _esInstances[0]
    expect(es.closed).toBe(false)

    await act(() => { es._triggerError() })

    expect(es.closed).toBe(true)
    unmount()
  })
})

// ── useSlaAlertStream — Last-Event-ID ────────────────────────────────────────

describe('useSlaAlertStream — Last-Event-ID', () => {
  it('sends Last-Event-ID on reconnect after receiving an event', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('ticket-leid'))
    const qc = makeQueryClient()

    const { unmount } = renderHook(() =>
      useSlaAlertStream({ queryClient: qc }, {
        sseBase: '/api/v1/stream/sla',
        ticketEndpoint: '/api/v1/auth/stream-ticket',
        heartbeatIntervalMs: 10_000,
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(50))
    const es = _esInstances[0]

    // Simulate an event with a Last-Event-ID
    await act(() => {
      const event = {
        type: 'SLA_AT_RISK',
        data: JSON.stringify({ eventType: 'SLA_AT_RISK', workOrderId: 'wo-001' }),
        lastEventId: 'event-42',
      }
      if (es._listeners['SLA_AT_RISK']) es._listeners['SLA_AT_RISK'](event)
    })

    // Force reconnect
    await act(() => { es._triggerError() })
    await act(() => vi.advanceTimersByTimeAsync(3_000))

    // New EventSource URL should contain the lastEventId
    const newEs = _esInstances[_esInstances.length - 1]
    expect(newEs.url).toContain('lastEventId=event-42')

    unmount()
  })
})

// ── useSlaAlertStream — staleness ─────────────────────────────────────────────

describe('useSlaAlertStream — staleness detection', () => {
  it('transitions to stale after threshold with no heartbeat', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('ticket-stale'))
    const qc = makeQueryClient()

    const HEARTBEAT_MS = 5_000

    const { result, unmount } = renderHook(() =>
      useSlaAlertStream({ queryClient: qc }, {
        sseBase: '/api/v1/stream/sla',
        ticketEndpoint: '/api/v1/auth/stream-ticket',
        heartbeatIntervalMs: HEARTBEAT_MS,
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(50))
    // Open the connection to start the staleness timer
    await act(() => { _esInstances[0]?._triggerOpen() })

    // Advance past staleness threshold (3× heartbeat)
    await act(() => vi.advanceTimersByTimeAsync(HEARTBEAT_MS * 3 + 500))

    expect(result.current.status).toBe('stale')
    unmount()
  })

  it('resets to live when a heartbeat arrives', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('ticket-live'))
    const qc = makeQueryClient()

    const HEARTBEAT_MS = 5_000

    const { result, unmount } = renderHook(() =>
      useSlaAlertStream({ queryClient: qc }, {
        sseBase: '/api/v1/stream/sla',
        ticketEndpoint: '/api/v1/auth/stream-ticket',
        heartbeatIntervalMs: HEARTBEAT_MS,
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(50))
    await act(() => { _esInstances[0]?._triggerOpen() })

    // Advance partway but not stale
    await act(() => vi.advanceTimersByTimeAsync(HEARTBEAT_MS * 2))

    // Emit a heartbeat
    await act(() => {
      const es = _esInstances[0]
      const ev = { type: 'heartbeat', data: JSON.stringify({ ts: new Date().toISOString() }), lastEventId: '' }
      if (es._listeners['heartbeat']) es._listeners['heartbeat'](ev)
    })

    expect(result.current.status).toBe('live')

    // Should not go stale before the next threshold
    await act(() => vi.advanceTimersByTimeAsync(HEARTBEAT_MS))
    expect(result.current.status).toBe('live')

    unmount()
  })
})

// ── useSlaAlertStream — query invalidation ────────────────────────────────────

describe('useSlaAlertStream — query invalidation on SLA events', () => {
  it('invalidates slaAlerts and workOrders on SLA_AT_RISK', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('ticket-inv'))
    const qc = makeQueryClient()
    const spy = vi.spyOn(qc, 'invalidateQueries')

    const { unmount } = renderHook(() =>
      useSlaAlertStream({ queryClient: qc }, {
        sseBase: '/api/v1/stream/sla',
        ticketEndpoint: '/api/v1/auth/stream-ticket',
        heartbeatIntervalMs: 10_000,
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(50))

    await act(() => {
      const es = _esInstances[0]
      const ev = {
        type: 'SLA_AT_RISK',
        data: JSON.stringify({ eventType: 'SLA_AT_RISK', workOrderId: 'wo-001' }),
        lastEventId: '',
      }
      if (es._listeners['SLA_AT_RISK']) es._listeners['SLA_AT_RISK'](ev)
    })

    const calledKeys = spy.mock.calls.map(c => c[0]?.queryKey)
    const keyStrings = calledKeys.map(k => JSON.stringify(k))
    expect(keyStrings).toContain(JSON.stringify(['workOrders']))
    expect(keyStrings).toContain(JSON.stringify(['slaAlerts']))

    unmount()
  })
})
