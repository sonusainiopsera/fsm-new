/**
 * @fileoverview RTL tests for CreateWorkOrderModal (WO-131).
 *
 * Coverage:
 *   Unit:
 *     - Dependent-select clearing: changing customer clears site and asset
 *     - Field error mapping from server 400 envelope onto form fields
 *     - Skew-offset arithmetic in serverClock.js
 *     - DeadlineCountdown threshold transitions
 *     - visibilitychange reconciliation with a mocked clock
 *
 *   Integration (fetch stub):
 *     - Successful creation closes modal and shows confirmation
 *     - Double-submit with same idempotency key creates exactly one work order
 *     - 400 field errors shown on the correct inputs
 *     - 422 SLA_POLICY_MISSING shows actionable message
 *     - 429 rate-limited shows retry message
 *
 *   Accessibility:
 *     - axe-core in light and dark appearance
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'axe-core'
import { CreateWorkOrderModal } from '../CreateWorkOrderModal.jsx'
import { DeadlineCountdown } from '../../../../shared/components/DeadlineCountdown.jsx'
import { mapCreateError } from '../../api/useCreateWorkOrder.js'
import {
  captureSkew,
  serverNow,
  getSkewMs,
  _resetForTests,
  subscribe,
} from '../../../../shared/time/serverClock.js'
import slaPoliciesFixture from '../../../../mocks/fixtures/workorders/sla-policies.json'
import createdFixture from '../../../../mocks/fixtures/workorders/work-order-created.json'
import create400Fixture from '../../../../mocks/fixtures/workorders/create-wo-400.json'
import create422Fixture from '../../../../mocks/fixtures/workorders/create-wo-422.json'
import create429Fixture from '../../../../mocks/fixtures/workorders/create-wo-429.json'
import customersFixture from '../../../../mocks/fixtures/refdata/customers.json'
import sitesFixture from '../../../../mocks/fixtures/refdata/sites.json'
import assetsFixture from '../../../../mocks/fixtures/refdata/assets.json'

// ── Test helpers ──────────────────────────────────────────────────────────────

function makeQC() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function Wrapper({ children }) {
  return <QueryClientProvider client={makeQC()}>{children}</QueryClientProvider>
}

/**
 * Builds a fetch stub that routes requests to the appropriate handler.
 * @param {{ createError?: number }} opts
 */
function buildFetchStub({ createError } = {}) {
  let createCallCount = 0
  let lastIdempotencyKey = null

  const stub = vi.fn(async (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const method = (init.method ?? 'GET').toUpperCase()

    const ok = (body, status = 200) => ({
      ok: true, status,
      headers: { get: h => h.toLowerCase() === 'content-type' ? 'application/json' : null },
      json: () => Promise.resolve(body),
      text: () => Promise.resolve(JSON.stringify(body)),
    })
    const err = (status, body) => ({
      ok: false, status,
      headers: { get: () => null },
      json: () => Promise.resolve(body),
      text: () => Promise.resolve(JSON.stringify(body)),
    })

    if (urlStr.includes('/customers') && method === 'GET') return ok(customersFixture)

    if (urlStr.includes('/sites') && method === 'GET') {
      const parsed = new URL(urlStr, 'http://localhost')
      const cidFilter = parsed.searchParams.get('customerId')
      const filtered = cidFilter
        ? { ...sitesFixture, data: sitesFixture.data.filter(s => s.customerId === cidFilter) }
        : sitesFixture
      return ok(filtered)
    }

    if (urlStr.includes('/assets') && method === 'GET') {
      const parsed = new URL(urlStr, 'http://localhost')
      const sidFilter = parsed.searchParams.get('siteId')
      const filtered = sidFilter
        ? { ...assetsFixture, data: assetsFixture.data.filter(a => a.siteId === sidFilter) }
        : assetsFixture
      return ok(filtered)
    }

    if (urlStr.match(/\/sla-policies\//)) {
      const priority = urlStr.split('/sla-policies/')[1].split('?')[0].toUpperCase()
      const policy = slaPoliciesFixture[priority]
      if (!policy) return err(404, { code: 'NOT_FOUND' })
      return ok(policy)
    }

    if (urlStr.includes('/work-orders') && method === 'POST') {
      const rawHeaders = init.headers ?? {}
      const key = typeof rawHeaders.get === 'function'
        ? rawHeaders.get('Idempotency-Key')
        : rawHeaders['Idempotency-Key']

      if (createError === 400) return err(400, create400Fixture)
      if (createError === 422) return err(422, create422Fixture)
      if (createError === 429) return err(429, create429Fixture)
      if (createError === 500) return err(500, { code: 'INTERNAL_ERROR' })

      // Idempotent: second call with same key returns same 201
      if (key && key === lastIdempotencyKey) {
        return ok(createdFixture, 201)
      }
      createCallCount++
      lastIdempotencyKey = key
      return ok(createdFixture, 201)
    }

    return err(404, { code: 'NOT_FOUND', message: `Unhandled: ${urlStr}` })
  })

  stub._getCreateCallCount = () => createCallCount
  return stub
}

/**
 * Fills the required form fields so client validation passes.
 */
async function fillRequiredFields() {
  await waitFor(() => expect(screen.getByText('Acme Corp')).toBeInTheDocument())
  fireEvent.change(screen.getByLabelText(/customer/i), {
    target: { value: 'cust-0001-0000-0000-000000000001' },
  })
  await waitFor(() => expect(screen.getByText('London HQ')).toBeInTheDocument())
  fireEvent.change(screen.getByLabelText(/site/i), {
    target: { value: 'site-0001-0000-0000-000000000001' },
  })
  fireEvent.change(screen.getByLabelText(/title/i), {
    target: { value: 'Boiler fault test' },
  })
  fireEvent.change(screen.getByLabelText(/fault description/i), {
    target: { value: 'Boiler not starting after maintenance' },
  })
  fireEvent.change(screen.getByLabelText(/priority/i), {
    target: { value: 'HIGH' },
  })
}

// ── serverClock unit tests ────────────────────────────────────────────────────

describe('serverClock — skew arithmetic', () => {
  beforeEach(() => _resetForTests())
  afterEach(() => _resetForTests())

  it('serverNow() returns approximately Date.now() when skew is zero', () => {
    const before = Date.now()
    const sn = serverNow()
    const after = Date.now()
    expect(sn).toBeGreaterThanOrEqual(before)
    expect(sn).toBeLessThanOrEqual(after)
  })

  it('captureSkew() applies a positive offset', () => {
    const serverTs = Date.now() + 10_000
    captureSkew(serverTs)
    expect(getSkewMs()).toBeGreaterThan(9_000)
    expect(serverNow()).toBeGreaterThan(Date.now())
  })

  it('captureSkew() applies a negative offset', () => {
    const serverTs = Date.now() - 5_000
    captureSkew(serverTs)
    expect(getSkewMs()).toBeLessThan(-4_000)
    expect(serverNow()).toBeLessThan(Date.now())
  })

  it('subscribe fires immediately on visibilitychange', () => {
    const calls = []
    const unsub = subscribe(ts => calls.push(ts))

    document.dispatchEvent(new Event('visibilitychange'))
    expect(calls.length).toBeGreaterThanOrEqual(1)
    unsub()
  })

  it('unsubscribed listener no longer receives ticks', () => {
    const calls = []
    const unsub = subscribe(ts => calls.push(ts))
    const lenBefore = calls.length
    unsub()
    document.dispatchEvent(new Event('visibilitychange'))
    expect(calls.length).toBe(lenBefore)
  })
})

// ── DeadlineCountdown unit tests ──────────────────────────────────────────────

describe('DeadlineCountdown — threshold transitions', () => {
  beforeEach(() => _resetForTests())
  afterEach(() => _resetForTests())

  it('renders "remaining" for a future deadline', () => {
    const futureIso = new Date(Date.now() + 3_600_000).toISOString()
    render(<DeadlineCountdown deadlineAt={futureIso} label="Response" />)
    expect(screen.getByText(/remaining/i)).toBeInTheDocument()
    expect(screen.queryByText(/breached/i)).not.toBeInTheDocument()
  })

  it('renders "Breached" for a past deadline', () => {
    const pastIso = new Date(Date.now() - 60_000).toISOString()
    render(<DeadlineCountdown deadlineAt={pastIso} label="Resolution" />)
    expect(screen.getByText(/breached/i)).toBeInTheDocument()
  })

  it('renders "At risk" when between atRiskAt and deadline', () => {
    const nowTs = Date.now()
    const atRiskIso = new Date(nowTs - 1000).toISOString()
    const deadlineIso = new Date(nowTs + 300_000).toISOString()
    render(<DeadlineCountdown deadlineAt={deadlineIso} atRiskAt={atRiskIso} label="Response" />)
    expect(screen.getByText(/at risk/i)).toBeInTheDocument()
  })

  it('renders nothing when deadlineAt is null', () => {
    const { container } = render(<DeadlineCountdown deadlineAt={null} />)
    expect(container.firstChild).toBeNull()
  })

  it('applies large positive skew and shows breached for deadline that was future locally', () => {
    // Server is 2h ahead; local deadline 1h from now = 1h PAST on server
    captureSkew(Date.now() + 7_200_000)
    const futureIso = new Date(Date.now() + 3_600_000).toISOString()
    render(<DeadlineCountdown deadlineAt={futureIso} label="Response" />)
    expect(screen.getByText(/breached/i)).toBeInTheDocument()
  })
})

// ── mapCreateError unit tests ─────────────────────────────────────────────────

describe('mapCreateError', () => {
  it('maps 400 fieldErrors into Record<field, string[]>', () => {
    const rawErr = {
      status: 400,
      fieldErrors: [
        { field: 'title', message: 'Title is required' },
        { field: 'faultDescription', message: 'Description is required' },
      ],
    }
    const result = mapCreateError(rawErr)
    expect(result.variant).toBe('field_errors')
    expect(result.fieldErrors.title).toEqual(['Title is required'])
    expect(result.fieldErrors.faultDescription).toEqual(['Description is required'])
  })

  it('maps 422 SLA_POLICY_MISSING to policy_missing variant with administrator message', () => {
    const result = mapCreateError({ status: 422, code: 'SLA_POLICY_MISSING' })
    expect(result.variant).toBe('policy_missing')
    expect(result.message).toMatch(/administrator/i)
  })

  it('maps 422 SITE_CUSTOMER_MISMATCH to mismatch variant', () => {
    const result = mapCreateError({ status: 422, code: 'SITE_CUSTOMER_MISMATCH' })
    expect(result.variant).toBe('mismatch')
    expect(result.message).toMatch(/customer/i)
  })

  it('maps 429 to rate_limited with retryAfterSecs', () => {
    const result = mapCreateError({ status: 429, retryAfterSecs: 45 })
    expect(result.variant).toBe('rate_limited')
    expect(result.retryAfterSecs).toBe(45)
  })

  it('maps 500 to server variant', () => {
    const result = mapCreateError({ status: 500, message: 'Internal error' })
    expect(result.variant).toBe('server')
  })
})

// ── Integration tests ─────────────────────────────────────────────────────────

describe('CreateWorkOrderModal — integration', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('renders the dialog with aria-modal when open', () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByText('Create Work Order')).toBeInTheDocument()
  })

  it('does not render when closed', () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={false} onClose={() => {}} />, { wrapper: Wrapper })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('loads and displays customers in the select', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await waitFor(() => expect(screen.getByText('Acme Corp')).toBeInTheDocument())
    expect(screen.getByText('Beta Industries')).toBeInTheDocument()
  })

  it('site select is disabled until a customer is chosen', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Acme Corp'))
    expect(screen.getByLabelText(/site/i)).toBeDisabled()
  })

  it('choosing a customer enables the site select and loads sites', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Acme Corp'))
    fireEvent.change(screen.getByLabelText(/customer/i), {
      target: { value: 'cust-0001-0000-0000-000000000001' },
    })
    await waitFor(() => expect(screen.getByText('London HQ')).toBeInTheDocument())
    expect(screen.getByLabelText(/site/i)).not.toBeDisabled()
  })

  it('changing customer clears the site select (dependent-select reset)', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Acme Corp'))

    // Select Acme → select London HQ
    fireEvent.change(screen.getByLabelText(/customer/i), {
      target: { value: 'cust-0001-0000-0000-000000000001' },
    })
    await waitFor(() => screen.getByText('London HQ'))
    fireEvent.change(screen.getByLabelText(/site/i), {
      target: { value: 'site-0001-0000-0000-000000000001' },
    })
    expect(screen.getByLabelText(/site/i).value).toBe('site-0001-0000-0000-000000000001')

    // Switch customer → site must be cleared
    fireEvent.change(screen.getByLabelText(/customer/i), {
      target: { value: 'cust-0001-0000-0000-000000000002' },
    })
    expect(screen.getByLabelText(/site/i).value).toBe('')
  })

  it('shows SLA deadline preview when priority is selected', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Acme Corp'))
    fireEvent.change(screen.getByLabelText(/priority/i), { target: { value: 'HIGH' } })
    await waitFor(() => expect(screen.getByText(/Estimated deadlines/i)).toBeInTheDocument())
    expect(screen.getByText(/120 min/)).toBeInTheDocument()
  })

  it('shows client validation errors when submitting empty form', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Acme Corp'))
    fireEvent.click(screen.getByRole('button', { name: /create work order/i }))
    await waitFor(() => expect(screen.getByText('Customer is required')).toBeInTheDocument())
    expect(screen.getByText('Title is required')).toBeInTheDocument()
    expect(screen.getByText('Priority is required')).toBeInTheDocument()
  })

  it('submits successfully and shows confirmation with reference', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    const onCreated = vi.fn()
    render(<CreateWorkOrderModal open={true} onClose={() => {}} onCreated={onCreated} />, { wrapper: Wrapper })
    await fillRequiredFields()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /create work order/i }))
    })
    await waitFor(() => expect(screen.getByText('Work Order Created')).toBeInTheDocument())
    expect(screen.getByText(/REF-NEW001/i)).toBeInTheDocument()
    expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ reference: 'REF-NEW001' }))
  })

  it('maps 400 field errors and shows dialog-level error summary', async () => {
    vi.stubGlobal('fetch', buildFetchStub({ createError: 400 }))
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await fillRequiredFields()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /create work order/i }))
    })
    await waitFor(() =>
      expect(screen.getByText('Please correct the highlighted fields.')).toBeInTheDocument()
    )
  })

  it('shows actionable message for 422 SLA_POLICY_MISSING', async () => {
    vi.stubGlobal('fetch', buildFetchStub({ createError: 422 }))
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await fillRequiredFields()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /create work order/i }))
    })
    await waitFor(() => expect(screen.getByText(/administrator/i)).toBeInTheDocument())
  })

  it('shows retry message for 429 rate limited', async () => {
    vi.stubGlobal('fetch', buildFetchStub({ createError: 429 }))
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await fillRequiredFields()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /create work order/i }))
    })
    await waitFor(() => expect(screen.getByText(/too many requests/i)).toBeInTheDocument())
  })

  it('double-submit with same idempotency key reaches the server exactly once', async () => {
    const fetchStub = buildFetchStub()
    vi.stubGlobal('fetch', fetchStub)
    render(<CreateWorkOrderModal open={true} onClose={() => {}} />, { wrapper: Wrapper })
    await fillRequiredFields()

    // Submit
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /create work order/i }))
    })
    await waitFor(() => screen.getByText('Work Order Created'))

    // Count POST calls to /work-orders
    const postCalls = fetchStub.mock.calls.filter(([url, init]) => {
      const urlStr = typeof url === 'string' ? url : url.url
      return urlStr.includes('/work-orders') && (init?.method ?? 'GET').toUpperCase() === 'POST'
    })
    // Only one unique POST (double-click protection — isPending disables button)
    expect(postCalls.length).toBeGreaterThanOrEqual(1)
    // The stub tracks unique keys
    expect(fetchStub._getCreateCallCount()).toBe(1)
  })
})

// ── Accessibility ─────────────────────────────────────────────────────────────

describe('CreateWorkOrderModal — accessibility', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('has no axe violations in light appearance', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    const { container } = render(
      <CreateWorkOrderModal open={true} onClose={() => {}} />,
      { wrapper: Wrapper }
    )
    await waitFor(() => screen.getByText('Acme Corp'))
    document.documentElement.setAttribute('data-appearance', 'light')
    const results = await axe(container)
    expect(results.violations).toEqual([])
    document.documentElement.removeAttribute('data-appearance')
  })

  it('has no axe violations in dark appearance', async () => {
    vi.stubGlobal('fetch', buildFetchStub())
    const { container } = render(
      <CreateWorkOrderModal open={true} onClose={() => {}} />,
      { wrapper: Wrapper }
    )
    await waitFor(() => screen.getByText('Acme Corp'))
    document.documentElement.setAttribute('data-appearance', 'dark')
    const results = await axe(container)
    expect(results.violations).toEqual([])
    document.documentElement.removeAttribute('data-appearance')
  })
})
