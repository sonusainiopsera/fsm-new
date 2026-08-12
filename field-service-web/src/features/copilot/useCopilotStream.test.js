/**
 * @fileoverview Unit tests for useCopilotStream (WO-179 AC-12).
 *
 * Tests cover:
 *   - Idle state on mount
 *   - STREAMING → COMPLETE on happy path
 *   - Token chunks accumulated and flushed (chunkIndex ordering)
 *   - Out-of-order chunks render in chunkIndex order (no duplicates)
 *   - STREAMING → REFUSED on no_grounded_basis event
 *   - STREAMING → DEGRADED on degraded event
 *   - STREAMING → DEGRADED on ticket fetch failure
 *   - STREAMING → CAPPED on 429 ticket response
 *   - STREAMING → PARTIAL on mid-stream EventSource error with prior text
 *   - STREAMING → DEGRADED on immediate EventSource error (no prior text)
 *   - basis and interactionId populated on complete event
 *   - dismiss() returns to IDLE and clears text
 *   - stripControlChars strips C0 controls, preserves newlines and tabs
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useCopilotStream, stripControlChars } from './useCopilotStream.js'
import { CopilotState } from './copilotStates.js'

// ── EventSource mock ──────────────────────────────────────────────────────────

function makeEventSourceMock() {
  const instances = []

  class MockEventSource {
    constructor(url) {
      this.url = url
      this._listeners = {}
      this.onerror = null
      instances.push(this)
    }
    addEventListener(name, fn) {
      if (!this._listeners[name]) this._listeners[name] = []
      this._listeners[name].push(fn)
    }
    close() { this._closed = true }
    _emit(name, data) {
      const payload = typeof data === 'string' ? data : JSON.stringify(data)
      const ev = { data: payload, type: name }
      ;(this._listeners[name] ?? []).forEach(fn => fn(ev))
    }
    _error() {
      if (this.onerror) this.onerror(new Event('error'))
    }
  }

  return { MockEventSource, instances }
}

// ── Fetch mock helpers ────────────────────────────────────────────────────────

function makeTicketFetch(ticket, status = 200, extraHeaders = {}) {
  return vi.fn(async (url) => {
    if (url.includes('/stream-ticket')) {
      return {
        ok: status >= 200 && status < 300,
        status,
        headers: { get: (h) => extraHeaders[h.toLowerCase()] ?? null },
        json: async () => status === 200 ? { ticket } : { code: 'ERR' },
      }
    }
    return { ok: false, status: 404, headers: { get: () => null }, json: async () => ({}) }
  })
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('useCopilotStream', () => {
  let esmock
  let instances

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame'] })
    esmock = makeEventSourceMock()
    instances = esmock.instances
    vi.stubGlobal('EventSource', esmock.MockEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    vi.restoreAllMocks()
    instances.length = 0
  })

  it('starts in IDLE state', () => {
    vi.stubGlobal('fetch', makeTicketFetch('t1'))
    const { result } = renderHook(() => useCopilotStream('wo-1'))
    expect(result.current.state).toBe(CopilotState.IDLE)
    expect(result.current.text).toBe('')
  })

  it('transitions IDLE → STREAMING → COMPLETE on happy path', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-happy'))
    const { result } = renderHook(() => useCopilotStream('wo-1'))

    await act(async () => { result.current.start() })
    expect(result.current.state).toBe(CopilotState.STREAMING)

    const es = instances[0]
    act(() => {
      es._emit('token', { chunkIndex: 0, text: 'Hello ', interactionId: 'iact-1' })
      es._emit('token', { chunkIndex: 1, text: 'world.' })
    })
    await act(async () => { vi.runAllTicks() })
    vi.runAllTimers()
    await act(async () => {})

    act(() => {
      es._emit('complete', {
        interactionId: 'iact-1',
        basis: { assetTag: 'BOILER-1', priorWorkOrders: [{ workOrderId: 'wo-old', reference: 'WO-OLD' }] },
      })
    })

    await waitFor(() => expect(result.current.state).toBe(CopilotState.COMPLETE))
    expect(result.current.interactionId).toBe('iact-1')
    expect(result.current.basis?.assetTag).toBe('BOILER-1')
  })

  it('renders out-of-order chunks in chunkIndex order without duplicates', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-ooo'))
    const { result } = renderHook(() => useCopilotStream('wo-2'))

    await act(async () => { result.current.start() })

    const es = instances[0]
    act(() => {
      es._emit('token', { chunkIndex: 2, text: 'C' })
      es._emit('token', { chunkIndex: 0, text: 'A' })
      es._emit('token', { chunkIndex: 1, text: 'B' })
      // Duplicate of chunk 0 — must be ignored
      es._emit('token', { chunkIndex: 0, text: 'A' })
    })
    vi.runAllTimers()
    await act(async () => {})

    act(() => { es._emit('complete', { interactionId: 'iact-2', basis: null }) })
    await waitFor(() => expect(result.current.state).toBe(CopilotState.COMPLETE))
    expect(result.current.text).toBe('ABC')
  })

  it('transitions to REFUSED on no_grounded_basis event', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-refused'))
    const { result } = renderHook(() => useCopilotStream('wo-3'))

    await act(async () => { result.current.start() })
    const es = instances[0]
    act(() => { es._emit('no_grounded_basis', {}) })
    await waitFor(() => expect(result.current.state).toBe(CopilotState.REFUSED))
  })

  it('transitions to DEGRADED on degraded event', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-degraded'))
    const { result } = renderHook(() => useCopilotStream('wo-4'))

    await act(async () => { result.current.start() })
    const es = instances[0]
    act(() => { es._emit('degraded', {}) })
    await waitFor(() => expect(result.current.state).toBe(CopilotState.DEGRADED))
  })

  it('transitions to DEGRADED on ticket fetch failure (non-200)', async () => {
    vi.stubGlobal('fetch', makeTicketFetch(null, 503))
    const { result } = renderHook(() => useCopilotStream('wo-5'))

    await act(async () => { result.current.start() })
    await waitFor(() => expect(result.current.state).toBe(CopilotState.DEGRADED))
    // No EventSource opened
    expect(instances).toHaveLength(0)
  })

  it('transitions to CAPPED on 429 ticket response', async () => {
    vi.stubGlobal('fetch', makeTicketFetch(null, 429, { 'retry-after': '3600' }))
    const { result } = renderHook(() => useCopilotStream('wo-6'))

    await act(async () => { result.current.start() })
    await waitFor(() => expect(result.current.state).toBe(CopilotState.CAPPED))
    expect(result.current.retryAfterSeconds).toBe(3600)
  })

  it('transitions to PARTIAL on mid-stream EventSource error with prior text', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-mid'))
    const { result } = renderHook(() => useCopilotStream('wo-7'))

    await act(async () => { result.current.start() })
    const es = instances[0]
    act(() => { es._emit('token', { chunkIndex: 0, text: 'Partial text.' }) })
    vi.runAllTimers()
    await act(async () => {})

    act(() => { es._error() })
    await waitFor(() => expect(result.current.state).toBe(CopilotState.PARTIAL))
    expect(result.current.text).toBe('Partial text.')
  })

  it('transitions to DEGRADED on immediate EventSource error with no prior text', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-imm'))
    const { result } = renderHook(() => useCopilotStream('wo-8'))

    await act(async () => { result.current.start() })
    const es = instances[0]
    act(() => { es._error() })
    await waitFor(() => expect(result.current.state).toBe(CopilotState.DEGRADED))
  })

  it('dismiss() resets to IDLE and clears text', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-dismiss'))
    const { result } = renderHook(() => useCopilotStream('wo-9'))

    await act(async () => { result.current.start() })
    const es = instances[0]
    act(() => { es._emit('token', { chunkIndex: 0, text: 'some text' }) })
    vi.runAllTimers()
    await act(async () => {})

    act(() => { result.current.dismiss() })
    await waitFor(() => {
      expect(result.current.state).toBe(CopilotState.IDLE)
      expect(result.current.text).toBe('')
    })
  })

  it('second call to start() while STREAMING is a no-op', async () => {
    vi.stubGlobal('fetch', makeTicketFetch('t-noop'))
    const { result } = renderHook(() => useCopilotStream('wo-10'))

    await act(async () => { result.current.start() })
    expect(instances).toHaveLength(1)
    await act(async () => { result.current.start() }) // should not open second EventSource
    expect(instances).toHaveLength(1)
  })
})

// ── stripControlChars ─────────────────────────────────────────────────────────

describe('stripControlChars', () => {
  it('preserves normal text', () => {
    expect(stripControlChars('Hello world.')).toBe('Hello world.')
  })

  it('preserves newlines and tabs', () => {
    expect(stripControlChars('line1\nline2\ttabbed')).toBe('line1\nline2\ttabbed')
  })

  it('strips null bytes', () => {
    expect(stripControlChars('a\x00b')).toBe('ab')
  })

  it('strips C0 controls except HT/LF', () => {
    // 0x08 = backspace, 0x0B = vertical tab, 0x1F = unit separator
    expect(stripControlChars('\x08\x0B\x1Ftext')).toBe('text')
  })

  it('strips C1 controls (0x7F–0x9F)', () => {
    expect(stripControlChars('\x7F\x80\x9Ftext')).toBe('text')
  })

  it('does not strip HTML — caller renders as text node', () => {
    const raw = '<script>alert("xss")</script>'
    expect(stripControlChars(raw)).toBe(raw)
  })
})
