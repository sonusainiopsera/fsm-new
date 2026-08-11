/**
 * System integration tests — composed screen exercising mock transport through
 * all named state transitions: loading→success, loading→403, loading→409/422, stale/degraded.
 */
import { render, screen, act } from '@testing-library/react'
import { useState, useEffect } from 'react'
import { createMockTransport } from '../mocks/mockTransport.js'
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
})
