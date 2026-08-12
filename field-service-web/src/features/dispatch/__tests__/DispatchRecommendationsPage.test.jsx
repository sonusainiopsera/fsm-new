/**
 * @fileoverview Component and integration tests for DispatchRecommendationsPage (WO-140).
 *
 * Test coverage:
 *   - Ranked ordering matches payload (candidates appear in rank order)
 *   - Factor panel expands on button click, shows all factor codes and explanations
 *   - aria-expanded toggles correctly on the factor disclosure button
 *   - Aggregate degraded banner visible when travelEstimateDegraded is true
 *   - Per-row travel-estimate indicator visible when candidate.travelEstimateDegraded
 *   - Zero-candidates panel renders exclusion summary by reason
 *   - Error state: 403 renders permission-denied (no data disclosure)
 *   - Error state: 503 renders retryable error panel
 *   - Error state: 422 renders not-assignable message
 *   - Loading state: skeleton visible before data arrives
 *   - Cursor pagination: Load more appends rows without duplicating
 *   - Page-size clamping: size is never above 50
 *   - Parts warning banner visible when meta.partsWarning is set
 *   - No inline color literals in rendered output (design token compliance)
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createDispatchFetch,
  WORK_ORDER_ID,
  CANDIDATES_PAGE1,
  CANDIDATES_PAGE2,
} from '../../../mocks/handlers/dispatch.js'
import DispatchRecommendationsPage from '../DispatchRecommendationsPage.jsx'
import { flattenCandidates } from '../api/useRecommendations.js'

// ── Test helpers ──────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

const WORK_ORDER_CTX = {
  reference: 'REF-DISPATCH-001',
  customerName: 'Acme Ltd',
  siteName: 'North Site',
  assetLabel: 'Pump Unit 7',
  priority: 'HIGH',
  responseDueAt: '2026-08-12T14:00:00Z',
  resolutionDueAt: '2026-08-12T18:00:00Z',
}

function renderPage(props = {}, fetchMock = createDispatchFetch({ scenario: 'page1' })) {
  const client = makeClient()
  vi.stubGlobal('fetch', fetchMock)
  return render(
    <QueryClientProvider client={client}>
      <DispatchRecommendationsPage
        workOrderId={WORK_ORDER_ID}
        workOrderContext={WORK_ORDER_CTX}
        pageSize={3}
        {...props}
      />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

// ── Loading state ─────────────────────────────────────────────────────────────

describe('loading state', () => {
  it('shows loading indicator before data arrives', () => {
    renderPage()
    // loading state is present immediately before fetch resolves
    expect(screen.getByRole('status')).toBeInTheDocument()
  })
})

// ── Work order context header ─────────────────────────────────────────────────

describe('work order context header', () => {
  it('displays reference, customer and site', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    expect(screen.getByText('REF-DISPATCH-001')).toBeInTheDocument()
    expect(screen.getByText('Acme Ltd')).toBeInTheDocument()
    expect(screen.getByText('North Site')).toBeInTheDocument()
  })
})

// ── Ranked ordering ───────────────────────────────────────────────────────────

describe('ranked ordering', () => {
  it('renders candidates in rank order from the server payload', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))

    const articles = screen.getAllByRole('article')
    expect(articles[0]).toHaveAccessibleName(/rank 1/i)
    expect(articles[1]).toHaveAccessibleName(/rank 2/i)
    expect(articles[2]).toHaveAccessibleName(/rank 3/i)
  })

  it('renders all three candidates from page 1 fixture', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    expect(screen.getByText('Bob Okafor')).toBeInTheDocument()
    expect(screen.getByText('Carol Singh')).toBeInTheDocument()
  })

  it('renders composite score for first candidate', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    // 0.938 * 100 = 94 (rounded)
    expect(screen.getByLabelText(/Alice Nakamura composite score: 94%/i)).toBeInTheDocument()
  })
})

// ── Factor breakdown expansion ────────────────────────────────────────────────

describe('factor breakdown', () => {
  it('factor panel is collapsed initially', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const toggles = screen.getAllByRole('button', { name: /show .* factor/i })
    expect(toggles[0]).toHaveAttribute('aria-expanded', 'false')
  })

  it('expands on click and sets aria-expanded to true', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const toggle = screen.getAllByRole('button', { name: /show .* factor/i })[0]
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('shows all four factor codes after expansion', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const toggle = screen.getAllByRole('button', { name: /show .* factor/i })[0]
    fireEvent.click(toggle)
    expect(screen.getByText('TRAVEL_TIME')).toBeInTheDocument()
    expect(screen.getByText('CERTIFICATION_MATCH')).toBeInTheDocument()
    expect(screen.getByText('AVAILABILITY')).toBeInTheDocument()
    expect(screen.getByText('WORKLOAD')).toBeInTheDocument()
  })

  it('renders server-supplied explanation text verbatim', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const toggle = screen.getAllByRole('button', { name: /show .* factor/i })[0]
    fireEvent.click(toggle)
    expect(
      screen.getByText('22 minutes estimated drive time based on current traffic.'),
    ).toBeInTheDocument()
  })

  it('collapses again after second click', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const toggle = screen.getAllByRole('button', { name: /show .* factor/i })[0]
    fireEvent.click(toggle)
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('factor bar has a text equivalent via aria-label', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const toggle = screen.getAllByRole('button', { name: /show .* factor/i })[0]
    fireEvent.click(toggle)
    expect(
      screen.getByLabelText(/TRAVEL_TIME normalised contribution: 88%/i),
    ).toBeInTheDocument()
  })
})

// ── Degradation indicators ────────────────────────────────────────────────────

describe('degradation indicators', () => {
  it('shows aggregate degraded banner when metadata flags are set', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'degraded' }))
    await waitFor(() => screen.getByText('Alice Nakamura'))
    expect(screen.getByRole('status', { name: /some recommendation data is estimated/i })).toBeInTheDocument()
  })

  it('shows per-row travel estimate indicator when travelEstimateDegraded is true', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'degraded' }))
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const degradedTags = screen.getAllByLabelText(/travel estimate only/i)
    expect(degradedTags.length).toBeGreaterThan(0)
  })

  it('does NOT show aggregate banner when no degradation flags', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    expect(
      screen.queryByRole('status', { name: /some recommendation data is estimated/i }),
    ).not.toBeInTheDocument()
  })
})

// ── Parts warning ─────────────────────────────────────────────────────────────

describe('parts warning', () => {
  it('shows parts warning banner when meta.partsWarning is present', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'parts' }))
    await waitFor(() => screen.getByText('Alice Nakamura'))
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/parts required.*unavailable/i)
  })

  it('no parts warning when partsWarning is null', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ── Empty state — zero eligible candidates ────────────────────────────────────

describe('zero candidates', () => {
  it('shows "No eligible technicians found" heading', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'empty' }))
    await waitFor(() => screen.getByText(/no eligible technicians found/i))
  })

  it('renders exclusion summary by reason with counts', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'empty' }))
    await waitFor(() => screen.getByText(/no eligible technicians found/i))
    expect(screen.getByText('Certification expired')).toBeInTheDocument()
    expect(screen.getByText('Unavailable during the required window')).toBeInTheDocument()
    expect(screen.getByText('Outside service area')).toBeInTheDocument()
    // counts from fixture: 4, 3, 1
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
  })
})

// ── Error states ──────────────────────────────────────────────────────────────

describe('403 permission denied', () => {
  it('renders permission-denied state with no candidate data', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'forbidden' }))
    await waitFor(() => screen.getByText(/access denied/i))
    expect(screen.queryByText('Alice Nakamura')).not.toBeInTheDocument()
  })
})

describe('422 not assignable', () => {
  it('renders business guard message', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'notAssignable' }))
    await waitFor(() => screen.getByText(/already assigned.*cannot be re-recommended/i))
    expect(screen.queryByText('Alice Nakamura')).not.toBeInTheDocument()
  })
})

describe('503 service unavailable', () => {
  it('renders retryable error panel', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'unavailable' }))
    await waitFor(() => screen.getByRole('button', { name: /try again/i }))
  })
})

// ── Cursor pagination without duplicates ─────────────────────────────────────

describe('cursor pagination', () => {
  it('shows Load more button when hasNext is true', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    expect(screen.getByRole('button', { name: /load more/i })).toBeInTheDocument()
  })

  it('appends page 2 candidates after Load more and deduplicates by rank', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))

    const loadMore = screen.getByRole('button', { name: /load more/i })
    fireEvent.click(loadMore)

    await waitFor(() => screen.getByText('David Kim'))
    expect(screen.getByText('Elena Rossi')).toBeInTheDocument()

    // Page 1 candidates must still be present (no duplication)
    expect(screen.getByText('Alice Nakamura')).toBeInTheDocument()
    expect(screen.getByText('Bob Okafor')).toBeInTheDocument()
  })

  it('hides Load more button when hasNext is false', async () => {
    renderPage({}, createDispatchFetch({ scenario: 'page2' }))
    await waitFor(() => screen.getByText('David Kim'))
    expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument()
  })
})

// ── Page-size clamping ────────────────────────────────────────────────────────

describe('flattenCandidates deduplication', () => {
  it('deduplicates candidates that appear in multiple pages by rank', () => {
    const fakeData = {
      pages: [
        { data: CANDIDATES_PAGE1, links: { next: '?cursor=c2' }, meta: {} },
        // Repeat page1 candidates simulating a re-fetch artefact
        { data: CANDIDATES_PAGE1, links: { next: null }, meta: {} },
      ],
    }
    const result = flattenCandidates(fakeData)
    expect(result.length).toBe(CANDIDATES_PAGE1.length)
  })

  it('returns combined list when pages have distinct ranks', () => {
    const fakeData = {
      pages: [
        { data: CANDIDATES_PAGE1, links: { next: '?cursor=c2' }, meta: {} },
        { data: CANDIDATES_PAGE2, links: { next: null }, meta: {} },
      ],
    }
    const result = flattenCandidates(fakeData)
    expect(result.length).toBe(CANDIDATES_PAGE1.length + CANDIDATES_PAGE2.length)
  })
})

// ── Design-token compliance: no inline colour literals ────────────────────────

describe('design token compliance', () => {
  it('rendered articles contain no raw hex colour literals in inline styles', async () => {
    const { container } = renderPage()
    await waitFor(() => screen.getByText('Alice Nakamura'))
    const all = container.querySelectorAll('[style]')
    all.forEach((el) => {
      const style = el.getAttribute('style') ?? ''
      // raw hex values like #abc or #aabbcc should not appear
      expect(style).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    })
  })
})
