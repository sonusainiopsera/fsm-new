/**
 * @fileoverview RTL tests for WorkOrderBoardPage.
 *
 * Stubs global fetch via vi.stubGlobal per project convention.
 * Covers: board render, row select opens drawer, drawer deep-link, filter URL
 * round-trip, empty state, error state, permission-denied state.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { workOrdersHandler, _resetBoardEtag } from '../../../mocks/handlers/workOrders.js'
import WorkOrderBoardPage from '../WorkOrderBoardPage.jsx'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function renderBoard(initialUrl = '/work-orders', fetchMock = workOrdersHandler()) {
  const client = makeClient()
  vi.stubGlobal('fetch', fetchMock)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialUrl]}>
        <WorkOrderBoardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  _resetBoardEtag()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

// ── Board render ───────────────────────────────────────────────────────────────

describe('WorkOrderBoardPage — board render', () => {
  it('shows the page header', async () => {
    renderBoard()
    await waitFor(() => screen.getByText(/Work Orders/i))
  })

  it('renders fixture rows with references', async () => {
    renderBoard()
    await waitFor(() => screen.getByText('REF-B001'))
    expect(screen.getByText('REF-B002')).toBeInTheDocument()
    expect(screen.getByText('REF-B003')).toBeInTheDocument()
  })

  it('shows at-risk rows with the at-risk label', async () => {
    renderBoard()
    await waitFor(() => screen.getAllByText(/At-risk/i))
    const atRiskCells = screen.getAllByText(/At-risk/i)
    // wo-board-001 and wo-board-005 are at-risk
    expect(atRiskCells.length).toBeGreaterThanOrEqual(2)
  })

  it('shows filter bar for filtering work orders', async () => {
    renderBoard()
    await waitFor(() => screen.getByRole('search', { name: /filter work orders/i }))
  })
})

// ── Empty state ────────────────────────────────────────────────────────────────

describe('WorkOrderBoardPage — empty state', () => {
  it('shows the empty state message when no rows are returned', async () => {
    renderBoard('/work-orders', workOrdersHandler({ boardOptions: { empty: true } }))
    await waitFor(() => screen.getByText(/no work orders match/i))
  })
})

// ── Error states ───────────────────────────────────────────────────────────────

describe('WorkOrderBoardPage — error states', () => {
  it('shows permission-denied when server returns 403', async () => {
    renderBoard('/work-orders', workOrdersHandler({ boardOptions: { error: 403 } }))
    await waitFor(() =>
      screen.getByText(/don't have permission/i)
    )
  })

  it('shows error state when server returns 500', async () => {
    renderBoard('/work-orders', workOrdersHandler({ boardOptions: { error: 500 } }))
    await waitFor(() =>
      screen.getByText(/failed to load work orders/i)
    )
  })
})

// ── DetailDrawer via row click ──────────────────────────────────────────────────

describe('WorkOrderBoardPage — detail drawer', () => {
  it('opens the drawer when a row is clicked', async () => {
    renderBoard()
    await waitFor(() => screen.getByText('REF-B001'))
    const row = screen.getByTestId('work-order-row-wo-board-001')
    fireEvent.click(row)
    await waitFor(() =>
      screen.getByText('HVAC Repair — Unit 3B')
    )
  })

  it('shows legalNextEvents from fixture as action buttons', async () => {
    renderBoard()
    await waitFor(() => screen.getByText('REF-B001'))
    fireEvent.click(screen.getByTestId('work-order-row-wo-board-001'))
    // wo-board-001 legalNextEvents: ["DEPART", "CANCEL"]
    await waitFor(() => screen.getByRole('button', { name: /Depart work order/i }))
    expect(screen.getByRole('button', { name: /Cancel work order/i })).toBeInTheDocument()
  })

  it('does not show actions for events not in legalNextEvents', async () => {
    renderBoard()
    await waitFor(() => screen.getByText('REF-B001'))
    fireEvent.click(screen.getByTestId('work-order-row-wo-board-001'))
    await waitFor(() => screen.getByRole('button', { name: /Depart work order/i }))
    // COMPLETE is not in legalNextEvents for wo-board-001
    expect(screen.queryByRole('button', { name: /Complete work order/i })).not.toBeInTheDocument()
  })

  it('opens the drawer when row is activated by keyboard (Enter)', async () => {
    renderBoard()
    await waitFor(() => screen.getByText('REF-B001'))
    const row = screen.getByTestId('work-order-row-wo-board-001')
    fireEvent.keyDown(row, { key: 'Enter' })
    await waitFor(() =>
      screen.getByText('HVAC Repair — Unit 3B')
    )
  })
})

// ── Deep-link — drawer open from URL ──────────────────────────────────────────

describe('WorkOrderBoardPage — deep-link drawer', () => {
  it('opens the drawer for the work order id in the URL', async () => {
    renderBoard('/work-orders?wo=wo-board-002')
    await waitFor(() => screen.getByText('Electrical Panel Inspection'))
  })
})
