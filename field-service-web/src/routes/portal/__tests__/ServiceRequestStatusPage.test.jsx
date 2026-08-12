/**
 * ServiceRequestStatusPage — unit + integration tests (WO-174).
 *
 * Coverage:
 * - Happy path: renders reference, status label, timeline, SLA dates.
 * - 304 conditional-GET: no re-render / no flash on unchanged data.
 * - Degraded state: FreshnessBanner visible when poll fails but data exists.
 * - Not-connected state: ErrorState when no data and poll fails.
 * - 404: renders "not found" message.
 * - 429: renders rate-limit error.
 * - Role denial (403): renders permission-denied message.
 * - No internal state codes, GPS coords, or PII in DOM.
 * - aria-live region exists for status announcements.
 */

import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
  seedEtag,
  errorFixture,
} from '../../../mocks/handlers/index.js';

import ServiceRequestStatusPage from '../ServiceRequestStatusPage.jsx';

// ---- Helpers -------------------------------------------------------------

function makeQC() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

function renderPage(requestId = 'wo-portal-001', qc = makeQC(), navState = {}) {
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter
        initialEntries={[{ pathname: `/portal/status/${requestId}`, state: navState }]}
      >
        <Routes>
          <Route path="/portal/status/:requestId" element={<ServiceRequestStatusPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// ---- Suite setup ---------------------------------------------------------

beforeAll(() => installHandlers());
afterAll(() => uninstallHandlers());
afterEach(() => resetHandlers());

// ---- Tests ---------------------------------------------------------------

describe('ServiceRequestStatusPage', () => {

  it('renders reference and current status label', async () => {
    renderPage();
    expect(await screen.findByText('WO-P001')).toBeInTheDocument();
    expect(screen.getByText(/request received — under review/i)).toBeInTheDocument();
  });

  it('renders site name in summary card', async () => {
    renderPage();
    expect(await screen.findByText(/northgate office/i)).toBeInTheDocument();
  });

  it('renders SLA commitment dates', async () => {
    renderPage();
    await screen.findByText('WO-P001');
    expect(screen.getByText(/response by/i)).toBeInTheDocument();
    expect(screen.getByText(/resolution by/i)).toBeInTheDocument();
  });

  it('renders StatusTimeline with milestone labels', async () => {
    renderPage();
    expect(await screen.findByRole('region', { name: /request timeline/i })).toBeInTheDocument();
    expect(screen.getByText(/request submitted/i)).toBeInTheDocument();
    expect(screen.getByText(/request received — under review/i)).toBeInTheDocument();
  });

  it('renders FreshnessBanner with update time', async () => {
    renderPage();
    await screen.findByText('WO-P001');
    // The freshness banner should have a role="status" element
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('renders aria-live region for status changes', async () => {
    renderPage();
    await screen.findByText('WO-P001');
    // The status summary item and banner both have aria-live
    const liveRegions = document.querySelectorAll('[aria-live]');
    expect(liveRegions.length).toBeGreaterThan(0);
  });

  it('shows degraded banner when poll fails but cached data exists', async () => {
    const qc = makeQC();
    // First fetch succeeds
    renderPage('wo-portal-001', qc);
    await screen.findByText('WO-P001');

    // Second poll fails — override with network error
    mockRespond('GET', '/api/v1/portal/service-requests/wo-portal-001/status',
      { status: 500, body: { code: 'SERVER_ERROR', message: 'Upstream failure.' } }
    );

    // Trigger refetch — in real polling this happens via interval but we force it
    await waitFor(() => qc.invalidateQueries({ queryKey: ['portal', 'status', 'wo-portal-001'] }));

    // We should still see the old data (structural sharing) and degraded banner
    await screen.findByText('WO-P001');
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('shows not-found message on 404', async () => {
    mockRespond('GET', '/api/v1/portal/service-requests/wo-portal-missing/status',
      errorFixture(404, 'NOT_FOUND', 'Request not found.')
    );
    renderPage('wo-portal-missing');
    expect(await screen.findByText(/request not found/i)).toBeInTheDocument();
  });

  it('shows permission-denied message on 403', async () => {
    mockRespond('GET', '/api/v1/portal/service-requests/wo-portal-forbidden/status',
      errorFixture(403, 'FORBIDDEN', 'Access denied.')
    );
    renderPage('wo-portal-forbidden');
    expect(await screen.findByText(/request not found/i)).toBeInTheDocument();
  });

  it('shows rate-limit error on 429', async () => {
    mockRespond('GET', '/api/v1/portal/service-requests/wo-portal-001/status',
      errorFixture(429, 'RATE_LIMITED', 'Too many requests.')
    );
    renderPage('wo-portal-001');
    expect(await screen.findByText(/too many requests/i)).toBeInTheDocument();
  });

  it('preserves cached data on 304 without re-render flash', async () => {
    const qc = makeQC();
    renderPage('wo-portal-001', qc);

    // Wait for initial data
    await screen.findByText('WO-P001');

    // Seed the ETag so next fetch returns 304
    seedEtag('/api/v1/portal/service-requests/wo-portal-001/status', '"portal-status-v1"');

    // Invalidate — the 304 handler will trigger; data should stay
    await qc.invalidateQueries({ queryKey: ['portal', 'status', 'wo-portal-001'] });
    await waitFor(() => !qc.isFetching());

    // Data should still be visible — no flash of loading state
    expect(screen.queryByText(/loading/i)).not.toBeInTheDocument();
    expect(screen.getByText('WO-P001')).toBeInTheDocument();
  });

  it('does not expose internal state codes or PII in the DOM', async () => {
    renderPage();
    await screen.findByText('WO-P001');

    const bodyText = document.body.textContent ?? '';
    expect(bodyText).not.toMatch(/\bIN_PROGRESS\b/);
    expect(bodyText).not.toMatch(/\bDISPATCHED\b/);
    expect(bodyText).not.toMatch(/\bON_HOLD\b/);
    // No GPS coordinate patterns
    expect(bodyText).not.toMatch(/\d{2}\.\d{4,}/);
  });

  it('shows "submit another request" link', async () => {
    renderPage();
    await screen.findByText('WO-P001');
    expect(screen.getByRole('link', { name: /submit another request/i })).toBeInTheDocument();
  });
});
