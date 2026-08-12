/**
 * DispatchRecommendationsPage — component + MSW flow tests.
 *
 * Covers:
 * - Ranked candidate ordering from payload (server is authoritative)
 * - Factor breakdown expansion/collapse with aria-expanded
 * - Degraded banner when travelEstimateDegraded/partsDataDegraded is true
 * - Inline degraded indicator per row
 * - Load more cursor pagination without row duplication
 * - Zero-candidate empty state with exclusion summary
 * - 403 permission-denied renders denied panel, no data
 * - 422 not-assignable renders explanation panel
 * - 503 degraded service renders retryable panel
 * - Loading skeleton shown on initial render
 */

import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
} from '../../../mocks/handlers/index.js';
import {
  WO_REC_ID,
  recommendationsPage1Fixture,
  recommendationsPage2Fixture,
  recommendationsZeroFixture,
  recommendationsDegradedFixture,
  recommendations403Fixture,
  recommendations422Fixture,
  recommendations503Fixture,
} from '../../../mocks/handlers/dispatchRecommendations.js';
import DispatchRecommendationsPage from '../DispatchRecommendationsPage.jsx';

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeQc() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

/**
 * @param {string} [workOrderId]
 * @param {QueryClient} [qc]
 */
function renderPage(workOrderId = WO_REC_ID, qc = makeQc()) {
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/dispatch/${workOrderId}/recommendations`]}>
        <Routes>
          <Route
            path="/dispatch/:workOrderId/recommendations"
            element={<DispatchRecommendationsPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const REC_PATH = `/api/v1/work-orders/${WO_REC_ID}/recommendations`;

// ── Setup/teardown ───────────────────────────────────────────────────────────

beforeAll(() => installHandlers());
afterEach(() => resetHandlers());
afterAll(() => uninstallHandlers());

// ── Tests ────────────────────────────────────────────────────────────────────

describe('DispatchRecommendationsPage', () => {

  it('renders ranked candidates in server order', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => expect(screen.getByText('Technician 1')).toBeInTheDocument());
    expect(screen.getByText('Technician 2')).toBeInTheDocument();
    expect(screen.getByText('Technician 3')).toBeInTheDocument();

    const ranks = screen.getAllByText(/^#\d/);
    expect(ranks[0]).toHaveTextContent('#1');
    expect(ranks[1]).toHaveTextContent('#2');
    expect(ranks[2]).toHaveTextContent('#3');
  });

  it('shows loading skeleton before data arrives', () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();
    // Loading state is present synchronously before the fetch settles
    expect(screen.getByRole('status', { name: /loading/i })).toBeInTheDocument();
  });

  it('expands factor breakdown on Show factors click', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));

    const expandBtns = screen.getAllByRole('button', { name: /show factors/i });
    expect(expandBtns[0]).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(expandBtns[0]);

    await waitFor(() => {
      expect(screen.getByText(/travel efficiency/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/all required certifications are current/i)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /hide factors/i })[0]).toHaveAttribute('aria-expanded', 'true');
  });

  it('collapses factor breakdown on Hide factors click', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));

    const expandBtn = screen.getAllByRole('button', { name: /show factors/i })[0];
    fireEvent.click(expandBtn);
    await waitFor(() => screen.getByText(/travel efficiency/i));

    const hideBtn = screen.getAllByRole('button', { name: /hide factors/i })[0];
    fireEvent.click(hideBtn);

    await waitFor(() =>
      expect(screen.queryByText(/travel efficiency/i)).not.toBeInTheDocument(),
    );
  });

  it('renders Load more button when hasNext is true', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));
    expect(screen.getByRole('button', { name: /load more/i })).toBeInTheDocument();
  });

  it('appends second page without duplicating rows', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));

    // Register page 2 before clicking Load more (one-shot override)
    mockRespond('GET', REC_PATH, recommendationsPage2Fixture());
    fireEvent.click(screen.getByRole('button', { name: /load more/i }));

    await waitFor(() => expect(screen.getByText('Technician 4')).toBeInTheDocument());
    // Page 1 candidates still present
    expect(screen.getByText('Technician 1')).toBeInTheDocument();
    expect(screen.getByText('Technician 2')).toBeInTheDocument();
    // Page 2 candidates present
    expect(screen.getByText('Technician 5')).toBeInTheDocument();
    // No duplicates — each technicianId appears exactly once
    expect(screen.getAllByText('Technician 1')).toHaveLength(1);
    // Load more gone when hasNext is false
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument(),
    );
  });

  it('renders aggregate degraded banner when travelEstimateDegraded is true', async () => {
    mockRespond('GET', REC_PATH, recommendationsDegradedFixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));
    // The degraded banner should be present
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.getByText(/travel times are estimates only/i)).toBeInTheDocument();
  });

  it('renders per-row degraded indicator on affected candidates', async () => {
    mockRespond('GET', REC_PATH, recommendationsDegradedFixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));
    const indicators = screen.getAllByLabelText(/travel estimate only/i);
    expect(indicators.length).toBeGreaterThan(0);
  });

  it('renders zero-candidate empty state with exclusion summary', async () => {
    mockRespond('GET', REC_PATH, recommendationsZeroFixture());
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/no eligible candidates/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/certification expired/i)).toBeInTheDocument();
  });

  it('renders permission-denied state on 403, shows no candidate data', async () => {
    mockRespond('GET', REC_PATH, recommendations403Fixture());
    renderPage();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/technician \d/i)).not.toBeInTheDocument();
  });

  it('renders not-assignable explanation on 422', async () => {
    mockRespond('GET', REC_PATH, recommendations422Fixture());
    renderPage();

    await waitFor(() =>
      expect(screen.getByText(/work order not assignable/i)).toBeInTheDocument(),
    );
    expect(screen.getByText(/already assigned/i)).toBeInTheDocument();
  });

  it('renders retryable degraded panel on 503 with a Retry button', async () => {
    mockRespond('GET', REC_PATH, recommendations503Fixture());
    renderPage();

    await waitFor(() =>
      expect(screen.getByText(/temporarily unavailable/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('renders work order context banner with customer, site, priority, deadlines', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));
    // Context banner from WO detail (default mock: wo-recs-001)
    expect(screen.getByText('WO-RECS-001')).toBeInTheDocument();
    expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('London HQ')).toBeInTheDocument();
    expect(screen.getByText('HIGH')).toBeInTheDocument();
  });

  it('renders composite score for each candidate', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));
    // Score for rank-1 candidate: 0.95 → "95.0%"
    expect(screen.getByLabelText(/composite score 95.0%/i)).toBeInTheDocument();
  });

  it('renders factor explanation text verbatim from server payload', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));
    fireEvent.click(screen.getAllByRole('button', { name: /show factors/i })[0]);

    await waitFor(() => {
      expect(screen.getByText('30 minutes estimated travel time from current location.')).toBeInTheDocument();
    });
  });

  it('factor bars have accessible meter role with aria-valuenow', async () => {
    mockRespond('GET', REC_PATH, recommendationsPage1Fixture());
    renderPage();

    await waitFor(() => screen.getByText('Technician 1'));
    fireEvent.click(screen.getAllByRole('button', { name: /show factors/i })[0]);

    await waitFor(() => {
      const meters = screen.getAllByRole('meter');
      expect(meters.length).toBeGreaterThan(0);
      meters.forEach(meter => {
        expect(meter).toHaveAttribute('aria-valuenow');
        expect(meter).toHaveAttribute('aria-valuemin', '0');
        expect(meter).toHaveAttribute('aria-valuemax', '100');
      });
    });
  });

});
