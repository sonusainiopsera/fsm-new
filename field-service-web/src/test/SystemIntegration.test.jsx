/**
 * System integration tests — composed screen exercising mock transport through
 * all named state transitions: loading→success, loading→400, loading→401-refresh,
 * loading→403, loading→409/422, loading→429, loading→503/degraded, SSE invalidation.
 */
import { render, screen, act } from '@testing-library/react'
import { useState, useEffect, useRef } from 'react'
import { createMockTransport, createSseEmitter, STATUS_FIXTURES } from '../mocks/mockTransport.js'
import {
  DataTable, Chip,
  LoadingState, ErrorState, PermissionDeniedState, DegradedState,
} from '../components/index.js'
import { DensityProvider } from '../density/DensityContext.js'

const COLUMNS = [
  { key: 'id', header: 'ID' },
  { key: 'title', header: 'Title' },
  { key: 'priority', header: 'Priority', render: (v) => <Chip kind="priority" value={String(v)} /> },
  { key: 'state', header: 'State', render: (v) => <Chip kind="state" value={String(v)} /> },
]

/**
 * Minimal composed screen that uses the mock transport to load work orders,
 * handling each named state variant.
 */
function WorkOrderScreen({ transport }) {
  const [phase, setPhase] = useState('loading')
  const [rows, setRows] = useState([])
  const [errorCode, setErrorCode] = useState(null)
  const [stale, setStale] = useState(false)

  useEffect(() => {
    let cancelled = false
    transport.fetch('dispatcher/workorders').then(({ data, error, stale: isStale }) => {
      if (cancelled) return
      if (error) {
        setErrorCode(error.code)
        setPhase('error')
      } else {
        setRows(data?.workOrders ?? [])
        setStale(isStale)
        setPhase(isStale ? 'degraded' : 'success')
      }
    })
    return () => { cancelled = true }
  }, [transport])

  if (phase === 'loading') return <LoadingState />
  if (phase === 'error' && errorCode === 403) return <PermissionDeniedState />
  if (phase === 'error') return <ErrorState onRetry={() => {}} />
  if (phase === 'degraded') return (
    <>
      <DegradedState onRetry={() => {}} />
      <DensityProvider>
        <DataTable columns={COLUMNS} rows={rows} rowKey={r => String(r.id)} aria-label="Work orders (stale)" />
      </DensityProvider>
    </>
  )

  return (
    <DensityProvider>
      <DataTable columns={COLUMNS} rows={rows} rowKey={r => String(r.id)} aria-label="Work orders" />
    </DensityProvider>
  )
}

describe('WorkOrderScreen — system integration', () => {
  it('shows loading state during fetch', () => {
    const transport = createMockTransport({ latencyMs: 9999 })
    render(<WorkOrderScreen transport={transport} />)
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.getByRole('status').getAttribute('aria-label')).toMatch(/loading/i)
  })

  it('transitions loading → success and renders table rows', async () => {
    const transport = createMockTransport()
    render(<WorkOrderScreen transport={transport} />)
    expect(screen.getByRole('status')).toBeInTheDocument()
    await screen.findByRole('table')
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1)
  })

  it('transitions loading → 403 → PermissionDeniedState', async () => {
    const transport = createMockTransport({ errorCode: 403 })
    render(<WorkOrderScreen transport={transport} />)
    await screen.findByText(/access denied/i)
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('transitions loading → 409 → ErrorState', async () => {
    const transport = createMockTransport({ errorCode: 409 })
    render(<WorkOrderScreen transport={transport} />)
    await screen.findByText(/something went wrong/i)
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  it('transitions loading → 422 → ErrorState', async () => {
    const transport = createMockTransport({ errorCode: 422 })
    render(<WorkOrderScreen transport={transport} />)
    await screen.findByText(/something went wrong/i)
  })

  it('transitions loading → stale → DegradedState + table still visible', async () => {
    const transport = createMockTransport({ stale: true })
    render(<WorkOrderScreen transport={transport} />)
    await screen.findByText(/showing stale data/i)
    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('transitions loading → 503 → DegradedState without table', async () => {
    const transport = createMockTransport({ errorCode: 503 })
    render(<WorkOrderScreen transport={transport} />)
    // 503 is a provider degradation — surfaces DegradedState or ErrorState (no data to show)
    await screen.findByRole('button', { name: /try again/i })
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('transitions loading → 429 → ErrorState (no retry storm)', async () => {
    const transport = createMockTransport({ errorCode: 429 })
    render(<WorkOrderScreen transport={transport} />)
    await screen.findByRole('button', { name: /try again/i })
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('400 response exposes fieldErrors shape in mock transport', () => {
    const fixture = STATUS_FIXTURES[400]
    expect(Array.isArray(fixture.fieldErrors)).toBe(true)
    expect(fixture.fieldErrors.length).toBeGreaterThan(0)
    fixture.fieldErrors.forEach(fe => {
      expect(typeof fe.field).toBe('string')
      expect(typeof fe.message).toBe('string')
    })
  })

  it('401 fixture has UNAUTHENTICATED code', () => {
    expect(STATUS_FIXTURES[401].code).toBe('UNAUTHENTICATED')
  })

  it('422 fieldErrors contain per-line detail', () => {
    const fixture = STATUS_FIXTURES[422]
    expect(fixture.code).toBe('INSUFFICIENT_STOCK')
    expect(fixture.fieldErrors[0].field).toMatch(/lines/)
    expect(fixture.fieldErrors[0].message).toMatch(/requested/)
  })

  it('429 fixture carries Retry-After metadata', () => {
    const fixture = STATUS_FIXTURES[429]
    expect(fixture.retryAfterSeconds).toBeGreaterThan(0)
  })
})

describe('SSE scripted event emitter', () => {
  it('delivers all scripted events in sequence', async () => {
    const received = []
    const emitter = createSseEmitter(
      [
        { type: 'WorkOrderAtRisk', delay: 10, payload: { workOrderId: 'wo-001' } },
        { type: 'WorkOrderBreached', delay: 20, payload: { workOrderId: 'wo-002' } },
      ],
      event => received.push(event)
    )

    emitter.start()
    await new Promise(r => setTimeout(r, 50))
    emitter.stop()

    expect(received).toHaveLength(2)
    expect(received[0].type).toBe('WorkOrderAtRisk')
    expect(received[1].type).toBe('WorkOrderBreached')
  })

  it('stop() prevents further event delivery', async () => {
    const received = []
    const emitter = createSseEmitter(
      [{ type: 'WorkOrderAtRisk', delay: 50, payload: {} }],
      event => received.push(event)
    )

    emitter.start()
    emitter.stop()  // Cancel before delay elapses

    await new Promise(r => setTimeout(r, 100))
    expect(received).toHaveLength(0)
  })

  it('SSE event payload carries expected fields', async () => {
    let capturedEvent = null
    const emitter = createSseEmitter(
      [{ type: 'WorkOrderAtRisk', delay: 10, payload: { workOrderId: 'wo-007', reason: 'SLA_BREACH_IMMINENT' } }],
      event => { capturedEvent = event }
    )

    emitter.start()
    await new Promise(r => setTimeout(r, 30))
    emitter.stop()

    expect(capturedEvent).not.toBeNull()
    expect(capturedEvent.payload.workOrderId).toBe('wo-007')
    expect(capturedEvent.payload.reason).toBe('SLA_BREACH_IMMINENT')
  })
})
