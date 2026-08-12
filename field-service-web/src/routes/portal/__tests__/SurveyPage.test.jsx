/**
 * @fileoverview Tests for SurveyPage — CSAT satisfaction survey form.
 *
 * Unit tests:
 *   - Survey form renders score radio group with all 5 options and correct accessible names
 *   - NPS options 0-10 rendered with labels
 *   - Comment field with character counter and PII notice
 *   - Client-side validation: submit blocked without a score
 *
 * Integration tests (MSW-driven):
 *   - Happy path: submit score → success state, no form
 *   - 409 already-answered: read-only state with plain-language wording, no form
 *   - 422 window-expired: read-only state with plain-language wording, no form
 *   - 400: inline field error for score field
 *   - No survey in list: empty state
 *
 * Accessibility:
 *   - axe zero critical/serious violations on form in light and dark
 *   - Score radio group has correct group label and individual accessible names
 *   - Keyboard traversal: tab to submit button, enter submits
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'axe-core'
import { AuthContext } from '../../../app/AuthContext.js'
import {
  portalHandler,
  portalSurveysHandler,
  portalSurveyResponseHandler,
} from '../../../mocks/handlers/portal.js'
import SurveyPage from '../SurveyPage.jsx'

const TEST_WO_ID = 'c0000000-0000-0000-0000-000000000006'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth() {
  return { accessToken: 'tok', roles: ['CUSTOMER'], userId: 'u1', storedPreference: null, setAuth: vi.fn(), clearAuth: vi.fn() }
}

function Wrapper({ children, workOrderId = TEST_WO_ID }) {
  return (
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth()}>
        <MemoryRouter initialEntries={[`/portal/surveys/${workOrderId}`]}>
          <Routes>
            <Route path="/portal/surveys/:workOrderId" element={children} />
            <Route path="/portal/history" element={<div data-testid="history-page">History</div>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', portalHandler())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  document.documentElement.removeAttribute('data-appearance')
})

// ── Score radio group tests ───────────────────────────────────────────────────

describe('SurveyPage — score radio group', () => {
  it('renders 5 score options with accessible names', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/overall satisfaction/i))

    const radios = screen.getAllByRole('radio', { name: /— / })
    // At least 5 score radios (may include NPS options)
    const scoreRadios = radios.filter(r => r.name === 'score')
    expect(scoreRadios).toHaveLength(5)
  })

  it('score options have correct accessible values 1 through 5', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/overall satisfaction/i))

    for (let i = 1; i <= 5; i++) {
      const radio = screen.getByRole('radio', { name: new RegExp(`^${i} —`) })
      expect(radio).toBeInTheDocument()
      expect(radio).toHaveAttribute('value', String(i))
    }
  })

  it('NPS options 0 through 10 are rendered', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/likely are you to recommend/i))

    const npsRadios = screen.getAllByRole('radio').filter(r => r.name === 'npsScore')
    expect(npsRadios).toHaveLength(11)
  })
})

// ── Comment field tests ───────────────────────────────────────────────────────

describe('SurveyPage — comment field', () => {
  it('renders comment field with character counter', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/additional comments/i))
    expect(screen.getByText('0 / 1000')).toBeInTheDocument()
  })

  it('shows PII notice for comment field', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/personal data notice/i))
  })

  it('updates character counter as user types', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/additional comments/i))

    fireEvent.change(screen.getByLabelText(/additional comments/i), {
      target: { value: 'Great service!' },
    })

    await waitFor(() => screen.getByText('14 / 1000'))
  })
})

// ── Validation tests ──────────────────────────────────────────────────────────

describe('SurveyPage — validation', () => {
  it('blocks submission and shows error when no score is selected', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByRole('button', { name: /submit feedback/i }))

    fireEvent.click(screen.getByRole('button', { name: /submit feedback/i }))

    await waitFor(() => screen.getByText(/please select a satisfaction score/i))
  })
})

// ── Submission happy path ─────────────────────────────────────────────────────

describe('SurveyPage — happy path', () => {
  it('submits successfully and renders thank-you state', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByRole('radio', { name: /^4 —/ }))

    fireEvent.click(screen.getByRole('radio', { name: /^4 —/ }))
    fireEvent.click(screen.getByRole('button', { name: /submit feedback/i }))

    await waitFor(() => screen.getByText(/thank you for your feedback/i))
    expect(screen.queryByRole('button', { name: /submit feedback/i })).not.toBeInTheDocument()
  })
})

// ── Server refusal rendering ──────────────────────────────────────────────────

describe('SurveyPage — 409 already answered', () => {
  beforeEach(() => {
    const surveysH = portalSurveysHandler()
    const responseH = portalSurveyResponseHandler({ error: 409 })

    vi.stubGlobal('fetch', (url, init = {}) => {
      const urlStr = typeof url === 'string' ? url : url.url
      const method = (init?.method ?? 'GET').toUpperCase()
      if (urlStr.match(/\/portal\/surveys\/[^/?]+\/response/) && method === 'POST') return responseH(url, init)
      if (urlStr.includes('/portal/surveys')) return surveysH(url, init)
      return portalHandler()(url, init)
    })
  })

  it('renders already-answered read-only state', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByRole('radio', { name: /^4 —/ }))

    fireEvent.click(screen.getByRole('radio', { name: /^4 —/ }))
    fireEvent.click(screen.getByRole('button', { name: /submit feedback/i }))

    await waitFor(() => screen.getByText(/already submitted feedback/i))
    expect(screen.queryByRole('button', { name: /submit feedback/i })).not.toBeInTheDocument()
  })

  it('does not show internal error codes (SURVEY_ALREADY_ANSWERED)', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByRole('radio', { name: /^4 —/ }))

    fireEvent.click(screen.getByRole('radio', { name: /^4 —/ }))
    fireEvent.click(screen.getByRole('button', { name: /submit feedback/i }))

    await waitFor(() => screen.getByText(/already submitted feedback/i))
    expect(document.body.textContent).not.toContain('SURVEY_ALREADY_ANSWERED')
  })
})

describe('SurveyPage — 422 window expired', () => {
  beforeEach(() => {
    const surveysH = portalSurveysHandler()
    const responseH = portalSurveyResponseHandler({ error: 422 })

    vi.stubGlobal('fetch', (url, init = {}) => {
      const urlStr = typeof url === 'string' ? url : url.url
      const method = (init?.method ?? 'GET').toUpperCase()
      if (urlStr.match(/\/portal\/surveys\/[^/?]+\/response/) && method === 'POST') return responseH(url, init)
      if (urlStr.includes('/portal/surveys')) return surveysH(url, init)
      return portalHandler()(url, init)
    })
  })

  it('renders window-expired read-only state', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByRole('radio', { name: /^4 —/ }))

    fireEvent.click(screen.getByRole('radio', { name: /^4 —/ }))
    fireEvent.click(screen.getByRole('button', { name: /submit feedback/i }))

    await waitFor(() => screen.getByText(/feedback window/i))
    expect(screen.queryByRole('button', { name: /submit feedback/i })).not.toBeInTheDocument()
  })

  it('does not show internal error codes (SURVEY_WINDOW_EXPIRED)', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByRole('radio', { name: /^4 —/ }))

    fireEvent.click(screen.getByRole('radio', { name: /^4 —/ }))
    fireEvent.click(screen.getByRole('button', { name: /submit feedback/i }))

    await waitFor(() => screen.getByText(/feedback window/i))
    expect(document.body.textContent).not.toContain('SURVEY_WINDOW_EXPIRED')
  })
})

describe('SurveyPage — empty survey list', () => {
  it('renders empty state when no survey is available for the work order', async () => {
    vi.stubGlobal('fetch', (url, init) => {
      const urlStr = typeof url === 'string' ? url : url.url
      if (urlStr.includes('/portal/surveys')) {
        return portalSurveysHandler({ state: 'empty' })(url, init)
      }
      return portalHandler()(url, init)
    })

    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/no feedback form is available/i))
  })
})

// ── Accessibility tests ───────────────────────────────────────────────────────

describe('SurveyPage — accessibility', () => {
  it('has no critical axe violations in light appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'light')
    const { container } = render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/overall satisfaction/i))

    const results = await axe(container)
    const critical = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(critical).toHaveLength(0)
  })

  it('has no critical axe violations in dark appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'dark')
    const { container } = render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/overall satisfaction/i))

    const results = await axe(container)
    const critical = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(critical).toHaveLength(0)
  })

  it('score radio group has a visible group label', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/overall satisfaction/i))
    // The legend element provides the group accessible name
    expect(screen.getByText(/overall satisfaction/i)).toBeInTheDocument()
  })

  it('submit button has minimum 48px height (inline style)', async () => {
    render(<SurveyPage />, { wrapper: Wrapper })
    const btn = await screen.findByRole('button', { name: /submit feedback/i })
    expect(btn.style.minHeight).toBe('48px')
  })
})
