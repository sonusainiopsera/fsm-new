/**
 * @fileoverview useCopilotStream — explicit state machine for the copilot SSE stream (WO-179).
 *
 * Auth flow (WO-082 contract):
 *   1. Exchange Bearer token for a single-use stream ticket via POST /api/v1/auth/stream-ticket.
 *      The token NEVER appears in a URL.
 *   2. Open EventSource with ?ticket=XXX&workOrderId=XXX.
 *   3. Consume named events: token | complete | no_grounded_basis | degraded | error.
 *   4. Close EventSource on any terminal event or component unmount.
 *
 * INP protection: token chunks are accumulated in a ref and flushed to React state
 * on animation frames (≤16 ms batches) rather than on every arriving chunk, keeping
 * the main-thread budget inside 200 ms at p95.
 *
 * CLS protection: the caller is expected to reserve fixed height for the answer region
 * before streaming begins (skeleton). This hook does not manage layout.
 *
 * Out-of-order chunks: sorted by chunkIndex before display. Duplicates are de-duped.
 *
 * Security: model output is returned as plain strings. Callers must render as text nodes,
 * never via dangerouslySetInnerHTML.
 */

import { useRef, useState, useCallback, useEffect } from 'react'
import { getToken } from '../../api/tokenStore.js'
import { CopilotState } from './copilotStates.js'

const TICKET_ENDPOINT = '/api/v1/auth/stream-ticket'
const STREAM_BASE     = '/api/v1/copilot/stream'

/**
 * @typedef {{
 *   assetId?: string,
 *   assetTag?: string,
 *   priorWorkOrders?: Array<{ workOrderId: string, reference: string }>
 * }} CopilotBasis
 */

/**
 * @typedef {{
 *   state: import('./copilotStates.js').CopilotState,
 *   text: string,
 *   interactionId: string | null,
 *   basis: CopilotBasis | null,
 *   retryAfterSeconds: number | null,
 *   start: () => void,
 *   dismiss: () => void,
 * }} UseCopilotStreamResult
 */

/**
 * @param {string} workOrderId
 * @param {{
 *   ticketEndpoint?: string,
 *   streamBase?: string,
 * }} [options]
 * @returns {UseCopilotStreamResult}
 */
export function useCopilotStream(workOrderId, options = {}) {
  const ticketEndpoint = options.ticketEndpoint ?? TICKET_ENDPOINT
  const streamBase     = options.streamBase     ?? STREAM_BASE

  const [state,            setState]            = useState(/** @type {import('./copilotStates.js').CopilotState} */ (CopilotState.IDLE))
  const [text,             setText]             = useState('')
  const [interactionId,    setInteractionId]    = useState(/** @type {string|null} */ (null))
  const [basis,            setBasis]            = useState(/** @type {CopilotBasis|null} */ (null))
  const [retryAfterSeconds, setRetryAfterSeconds] = useState(/** @type {number|null} */ (null))

  // Accumulated chunks: Map<chunkIndex, text> — flushed to state on rAF
  const chunkMapRef     = useRef(/** @type {Map<number,string>} */ (new Map()))
  const rafRef          = useRef(/** @type {number|null} */ (null))
  const esRef           = useRef(/** @type {EventSource|null} */ (null))
  const abortRef        = useRef(/** @type {AbortController|null} */ (null))
  const mountedRef      = useRef(true)

  // Flush accumulated chunks to React state on the next animation frame
  const scheduleFlush = useCallback(() => {
    if (rafRef.current !== null) return
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null
      if (!mountedRef.current) return
      const sorted = [...chunkMapRef.current.entries()]
        .sort(([a], [b]) => a - b)
        .map(([, t]) => t)
        .join('')
      setText(stripControlChars(sorted))
    })
  }, [])

  const closeStream = useCallback(() => {
    if (esRef.current) {
      esRef.current.close()
      esRef.current = null
    }
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current)
      rafRef.current = null
    }
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
  }, [])

  const openStream = useCallback(async () => {
    if (!mountedRef.current) return

    chunkMapRef.current.clear()
    setText('')
    setBasis(null)
    setInteractionId(null)
    setRetryAfterSeconds(null)
    setState(CopilotState.STREAMING)

    // 1. Fetch single-use stream ticket
    const token = getToken()
    if (!token) {
      setState(CopilotState.DEGRADED)
      return
    }

    let ticket
    let retryAfter = null
    try {
      const ac = new AbortController()
      abortRef.current = ac
      const res = await fetch(ticketEndpoint, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        signal: ac.signal,
      })
      if (res.status === 429) {
        const ra = res.headers.get('Retry-After')
        retryAfter = ra ? Number(ra) : null
        if (mountedRef.current) {
          setRetryAfterSeconds(retryAfter)
          setState(CopilotState.CAPPED)
        }
        return
      }
      if (!res.ok) {
        if (mountedRef.current) setState(CopilotState.DEGRADED)
        return
      }
      const body = await res.json()
      ticket = body?.ticket
      if (!ticket) throw new Error('Missing ticket in response')
    } catch (err) {
      if (err?.name === 'AbortError') return
      if (mountedRef.current) setState(CopilotState.DEGRADED)
      return
    }

    if (!mountedRef.current) return

    // 2. Open EventSource with ticket (never the bearer token)
    const url = `${streamBase}?ticket=${encodeURIComponent(ticket)}&workOrderId=${encodeURIComponent(workOrderId)}`
    const es = new EventSource(url)
    esRef.current = es

    es.addEventListener('token', (ev) => {
      try {
        const payload = JSON.parse(ev.data)
        const idx = typeof payload.chunkIndex === 'number' ? payload.chunkIndex : chunkMapRef.current.size
        if (!chunkMapRef.current.has(idx)) {
          chunkMapRef.current.set(idx, String(payload.text ?? ''))
        }
        // Capture interactionId from first chunk that carries it
        if (payload.interactionId && mountedRef.current) {
          setInteractionId(payload.interactionId)
        }
        scheduleFlush()
      } catch { /* malformed chunk — skip */ }
    })

    es.addEventListener('complete', (ev) => {
      try {
        const payload = JSON.parse(ev.data)
        if (mountedRef.current) {
          if (payload.interactionId) setInteractionId(payload.interactionId)
          if (payload.basis)         setBasis(payload.basis)
          setState(CopilotState.COMPLETE)
        }
      } catch { /* skip */ }
      closeStream()
    })

    es.addEventListener('no_grounded_basis', () => {
      if (mountedRef.current) setState(CopilotState.REFUSED)
      closeStream()
    })

    es.addEventListener('degraded', () => {
      if (mountedRef.current) setState(CopilotState.DEGRADED)
      closeStream()
    })

    es.onerror = () => {
      // EventSource error: if we already have partial text, mark PARTIAL
      if (mountedRef.current) {
        const hasText = chunkMapRef.current.size > 0
        setState(hasText ? CopilotState.PARTIAL : CopilotState.DEGRADED)
      }
      closeStream()
    }
  }, [workOrderId, ticketEndpoint, streamBase, scheduleFlush, closeStream])

  const start = useCallback(() => {
    if (state === CopilotState.STREAMING) return
    openStream()
  }, [state, openStream])

  const dismiss = useCallback(() => {
    closeStream()
    if (mountedRef.current) {
      setState(CopilotState.IDLE)
      setText('')
      setBasis(null)
      setInteractionId(null)
      setRetryAfterSeconds(null)
      chunkMapRef.current.clear()
    }
  }, [closeStream])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      closeStream()
    }
  }, [closeStream])

  return { state, text, interactionId, basis, retryAfterSeconds, start, dismiss }
}

/**
 * Strips C0 and C1 control characters from model output.
 * Allows newlines (0x0A) and tabs (0x09) as safe whitespace.
 * Never parses HTML — caller must render as text nodes only.
 *
 * @param {string} raw
 * @returns {string}
 */
export function stripControlChars(raw) {
  // Remove C0 controls except HT (0x09) and LF (0x0A), and remove all C1 controls (0x7F–0x9F)
  // eslint-disable-next-line no-control-regex
  return raw.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F-\x9F]/g, '')
}
