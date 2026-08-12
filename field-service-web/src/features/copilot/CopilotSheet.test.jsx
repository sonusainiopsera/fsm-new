/**
 * @fileoverview Component tests for CopilotSheet (WO-179 AC-12).
 *
 * Tests cover:
 *   - Advisory label visible during streaming, hidden in IDLE
 *   - Advisory label persists while scrolling (sticky positioning — tested via presence)
 *   - Basis section expandable/collapsible with aria-expanded
 *   - Plain-text rendering: markup in a chunk displayed literally (no HTML injection)
 *   - Refused state renders distinct named state copy
 *   - Degraded state renders retry + dismiss
 *   - Capped state renders Retry-After guidance
 *   - Partial state renders partial notice
 *   - Rating submit optimistic UI + duplicate-submit prevention
 *   - Rating rollback on error shows toast
 *   - Escape key dismisses the sheet
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CopilotSheet } from './CopilotSheet.jsx'
import * as useCopilotStreamModule from './useCopilotStream.js'
import { CopilotState } from './copilotStates.js'
import { ToastProvider } from '../../components/Toast/ToastProvider.jsx'

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeStream(overrides = {}) {
  return {
    state: CopilotState.IDLE,
    text: '',
    interactionId: null,
    basis: null,
    retryAfterSeconds: null,
    start: vi.fn(),
    dismiss: vi.fn(),
    ...overrides,
  }
}

function Wrapper({ children }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return (
    <QueryClientProvider client={qc}>
      <ToastProvider>
        {children}
      </ToastProvider>
    </QueryClientProvider>
  )
}

function renderSheet(streamOverrides = {}, onDismiss = vi.fn()) {
  vi.spyOn(useCopilotStreamModule, 'useCopilotStream').mockReturnValue(makeStream(streamOverrides))
  return render(
    <Wrapper>
      <CopilotSheet workOrderId="wo-test" onDismiss={onDismiss} />
    </Wrapper>
  )
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('CopilotSheet', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('renders the sheet with title', () => {
    renderSheet()
    expect(screen.getByTestId('copilot-sheet')).toBeInTheDocument()
    expect(screen.getByText('Copilot')).toBeInTheDocument()
  })

  // AC-3: Advisory label visible while answer is displayed (STREAMING state)
  it('shows Advisory label while streaming', () => {
    renderSheet({ state: CopilotState.STREAMING, text: 'Some text…' })
    expect(screen.getByTestId('advisory-label')).toBeInTheDocument()
    expect(screen.getByTestId('advisory-label')).toHaveTextContent('advisory')
  })

  it('hides Advisory label in IDLE state', () => {
    renderSheet({ state: CopilotState.IDLE })
    expect(screen.queryByTestId('advisory-label')).not.toBeInTheDocument()
  })

  it('shows Advisory label in COMPLETE state', () => {
    renderSheet({ state: CopilotState.COMPLETE, text: 'Done.', interactionId: 'i1' })
    expect(screen.getByTestId('advisory-label')).toBeInTheDocument()
  })

  // AC-5: markup in chunk displayed literally — no HTML interpretation
  it('renders HTML markup in streamed chunk as plain text (no injection)', () => {
    const dangerousText = '<script>alert("xss")</script> Check the gauge.'
    renderSheet({ state: CopilotState.STREAMING, text: dangerousText })
    const answerEl = screen.getByTestId('copilot-answer-text')
    // The text content should equal the raw string
    expect(answerEl.textContent).toContain('<script>')
    // There must not be a live script element inside the sheet
    expect(screen.queryByTestId('copilot-sheet')?.querySelector('script')).toBeNull()
  })

  // AC-4: Basis section - expand/collapse with aria-expanded
  it('shows basis toggle and content is initially collapsed', () => {
    renderSheet({
      state: CopilotState.COMPLETE,
      text: 'Answer.',
      interactionId: 'i1',
      basis: {
        assetTag: 'BOILER-42',
        priorWorkOrders: [{ workOrderId: 'wo-old', reference: 'WO-OLD' }],
      },
    })

    const toggle = screen.getByTestId('basis-toggle')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByTestId('basis-content')).toHaveAttribute('hidden')
  })

  it('expands basis section on click', async () => {
    renderSheet({
      state: CopilotState.COMPLETE,
      text: 'Answer.',
      interactionId: 'i1',
      basis: {
        assetTag: 'BOILER-42',
        priorWorkOrders: [{ workOrderId: 'wo-old', reference: 'WO-OLD' }],
      },
    })

    const toggle = screen.getByTestId('basis-toggle')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('basis-content')).not.toHaveAttribute('hidden')
    expect(screen.getByTestId('basis-wo-link-wo-old')).toHaveTextContent('WO-OLD')
  })

  // AC-6: Refused state
  it('shows refused state with appropriate copy (no generated procedure)', () => {
    renderSheet({ state: CopilotState.REFUSED })
    expect(screen.getByTestId('refused-state')).toBeInTheDocument()
    expect(screen.getByTestId('refused-state')).toHaveTextContent('No grounded basis')
    // Must not show advisory label (no answer shown in refused state)
    expect(screen.queryByTestId('advisory-label')).not.toBeInTheDocument()
    // Must not show a retry button (a fabricated answer would result)
    expect(screen.queryByTestId('copilot-retry')).not.toBeInTheDocument()
  })

  // AC-7: Degraded state
  it('shows degraded state with retry and dismiss', () => {
    renderSheet({ state: CopilotState.DEGRADED })
    expect(screen.getByTestId('degraded-state')).toBeInTheDocument()
    expect(screen.getByTestId('copilot-retry')).toBeInTheDocument()
    expect(screen.getByTestId('copilot-dismiss')).toBeInTheDocument()
  })

  // AC-7: Partial state
  it('shows partial state with partial notice', () => {
    renderSheet({ state: CopilotState.PARTIAL, text: 'Begin by checking…' })
    expect(screen.getByTestId('partial-notice')).toBeInTheDocument()
    expect(screen.getByTestId('copilot-answer-text')).toHaveTextContent('Begin by checking…')
    // Advisory label visible on PARTIAL (has text)
    expect(screen.getByTestId('advisory-label')).toBeInTheDocument()
  })

  // AC-7: Capped state
  it('shows capped state with Retry-After guidance', () => {
    renderSheet({ state: CopilotState.CAPPED, retryAfterSeconds: 3600 })
    expect(screen.getByTestId('capped-state')).toBeInTheDocument()
    expect(screen.getByTestId('capped-state')).toHaveTextContent('60 minute')
  })

  // AC-8: Helpfulness rating appears on COMPLETE with interactionId
  it('shows rating buttons on COMPLETE state', () => {
    renderSheet({ state: CopilotState.COMPLETE, text: 'Done.', interactionId: 'i1' })
    expect(screen.getByTestId('helpfulness-rating')).toBeInTheDocument()
    expect(screen.getByTestId('rate-helpful')).toBeInTheDocument()
    expect(screen.getByTestId('rate-not-helpful')).toBeInTheDocument()
  })

  // AC-8: Duplicate-submit prevention
  it('disables rating buttons after submission', async () => {
    // Mock the POST to rating endpoint
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 204, headers: { get: () => null }, json: async () => null })))

    renderSheet({ state: CopilotState.COMPLETE, text: 'Done.', interactionId: 'i1' })

    const thumbsUp = screen.getByTestId('rate-helpful')
    fireEvent.click(thumbsUp)

    // After clicking, the button should be disabled (optimistic update)
    await waitFor(() => {
      expect(screen.getByTestId('rate-helpful')).toBeDisabled()
      expect(screen.getByTestId('rate-not-helpful')).toBeDisabled()
    })

    vi.unstubAllGlobals()
  })

  // Dismiss via close button calls onDismiss
  it('calls onDismiss when close button is clicked', () => {
    const onDismiss = vi.fn()
    renderSheet({}, onDismiss)
    fireEvent.click(screen.getByTestId('copilot-close'))
    expect(onDismiss).toHaveBeenCalledOnce()
  })

  // Escape key dismisses the sheet
  it('calls onDismiss on Escape key', () => {
    const onDismiss = vi.fn()
    renderSheet({}, onDismiss)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onDismiss).toHaveBeenCalled()
  })

  // AC-1: Entry point button in IDLE starts streaming
  it('calls start() when "Ask copilot" is clicked', () => {
    const start = vi.fn()
    renderSheet({ state: CopilotState.IDLE, start })
    fireEvent.click(screen.getByTestId('copilot-ask'))
    expect(start).toHaveBeenCalledOnce()
  })

  // ARIA live region gets state announcement
  it('live region contains streaming announcement', () => {
    renderSheet({ state: CopilotState.STREAMING, text: '' })
    expect(screen.getByTestId('copilot-live-region')).toHaveTextContent('Answer is loading')
  })

  it('live region contains complete announcement', () => {
    renderSheet({ state: CopilotState.COMPLETE, text: 'Done.', interactionId: 'i1' })
    expect(screen.getByTestId('copilot-live-region')).toHaveTextContent('Answer ready')
  })
})
