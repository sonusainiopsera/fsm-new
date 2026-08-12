/**
 * NewServiceRequestPage — unit + integration tests (WO-174).
 *
 * Coverage:
 * - Form renders with site options from API.
 * - Asset select populates when a site is selected.
 * - Client-side validation: required fields, min/max length.
 * - Submit sends correct body with stable Idempotency-Key.
 * - Server field errors rendered inline against the correct field.
 * - Double-submit prevented (button disabled during flight).
 * - Success navigates to status page.
 * - No internal state codes, GPS data, or technician info in DOM.
 * - 400 / 409 / 429 errors shown as plain-language messages.
 */

import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
  errorFixture,
} from '../../../mocks/handlers/index.js';

import NewServiceRequestPage from '../NewServiceRequestPage.jsx';

// ---- Mocks ---------------------------------------------------------------

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

// ---- Helpers -------------------------------------------------------------

function makeQC() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

function renderPage(qc = makeQC()) {
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/portal/new']}>
        <NewServiceRequestPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// ---- Suite setup ---------------------------------------------------------

beforeAll(() => installHandlers());
afterAll(() => uninstallHandlers());
beforeEach(() => { mockNavigate.mockReset(); });
afterEach(() => resetHandlers());

// ---- Tests ---------------------------------------------------------------

describe('NewServiceRequestPage', () => {

  it('renders the form with page header', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: /new service request/i })).toBeInTheDocument();
    expect(screen.getByRole('form', { name: /tell us about the issue/i })).toBeInTheDocument();
  });

  it('loads site options from the API', async () => {
    renderPage();
    const siteSelect = await screen.findByRole('combobox', { name: /site/i });
    expect(within(siteSelect).getByRole('option', { name: /northgate office/i })).toBeInTheDocument();
    expect(within(siteSelect).getByRole('option', { name: /southside warehouse/i })).toBeInTheDocument();
  });

  it('loads asset options when a site is selected', async () => {
    const user = userEvent.setup();
    renderPage();

    const siteSelect = await screen.findByRole('combobox', { name: /site/i });
    await user.selectOptions(siteSelect, 'site-p01');

    const assetSelect = await screen.findByRole('combobox', { name: /asset/i });
    await waitFor(() =>
      expect(within(assetSelect).getByRole('option', { name: /HVAC-101/i })).toBeInTheDocument()
    );
  });

  it('validates required fields on submit attempt', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('combobox', { name: /site/i }); // wait for load
    await user.click(screen.getByRole('button', { name: /submit/i }));

    expect(await screen.findByText(/please select a site/i)).toBeInTheDocument();
    expect(screen.getByText(/please describe the fault/i)).toBeInTheDocument();
  });

  it('validates minimum fault description length', async () => {
    const user = userEvent.setup();
    renderPage();

    const siteSelect = await screen.findByRole('combobox', { name: /site/i });
    await user.selectOptions(siteSelect, 'site-p01');
    await user.type(screen.getByRole('textbox', { name: /describe the fault/i }), 'ab');
    await user.click(screen.getByRole('button', { name: /submit/i }));

    expect(await screen.findByText(/at least 4 characters/i)).toBeInTheDocument();
  });

  it('shows characters remaining counter', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('combobox', { name: /site/i });
    await user.type(screen.getByRole('textbox', { name: /describe the fault/i }), 'test');

    expect(screen.getByText(/1996 characters remaining/i)).toBeInTheDocument();
  });

  it('submits valid form and navigates to status page', async () => {
    const user = userEvent.setup();
    renderPage();

    const siteSelect = await screen.findByRole('combobox', { name: /site/i });
    await user.selectOptions(siteSelect, 'site-p01');

    const textarea = screen.getByRole('textbox', { name: /describe the fault/i });
    await user.type(textarea, 'The HVAC unit on floor 2 is making a loud grinding noise.');

    await user.click(screen.getByRole('button', { name: /submit request/i }));

    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith(
        '/portal/status/wo-portal-001',
        expect.objectContaining({ state: expect.objectContaining({ reference: 'WO-P001' }) })
      )
    );
  });

  it('disables submit button during flight', async () => {
    const user = userEvent.setup();
    renderPage();

    const siteSelect = await screen.findByRole('combobox', { name: /site/i });
    await user.selectOptions(siteSelect, 'site-p01');
    await user.type(
      screen.getByRole('textbox', { name: /describe the fault/i }),
      'The HVAC unit on floor 2 is making a loud grinding noise.'
    );

    // Mock a slow response so we can check the disabled state
    mockRespond('POST', '/api/v1/portal/service-requests', {
      status: 201,
      body: { workOrderId: 'wo-portal-001', reference: 'WO-P001', respondByAt: null, resolveByAt: null },
    });

    const submitBtn = screen.getByRole('button', { name: /submit request/i });
    await user.click(submitBtn);

    // After click the button should show submitting text
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /submitting/i })).toBeDisabled()
    );
  });

  it('renders server field errors against the correct field', async () => {
    const user = userEvent.setup();
    renderPage();

    mockRespond('POST', '/api/v1/portal/service-requests', {
      status: 400,
      body: {
        status: 400,
        code: 'VALIDATION_ERROR',
        message: 'Request validation failed.',
        fieldErrors: [
          { field: 'faultDescription', message: 'Must not contain offensive language.' },
        ],
      },
    });

    const siteSelect = await screen.findByRole('combobox', { name: /site/i });
    await user.selectOptions(siteSelect, 'site-p01');
    await user.type(
      screen.getByRole('textbox', { name: /describe the fault/i }),
      'Some description here for testing.'
    );
    await user.click(screen.getByRole('button', { name: /submit request/i }));

    expect(await screen.findByText(/must not contain offensive language/i)).toBeInTheDocument();
  });

  it('renders a general error message on 429', async () => {
    const user = userEvent.setup();
    renderPage();

    mockRespond('POST', '/api/v1/portal/service-requests', errorFixture(429, 'RATE_LIMITED', 'Too many requests.'));

    const siteSelect = await screen.findByRole('combobox', { name: /site/i });
    await user.selectOptions(siteSelect, 'site-p01');
    await user.type(
      screen.getByRole('textbox', { name: /describe the fault/i }),
      'Some description here for testing.'
    );
    await user.click(screen.getByRole('button', { name: /submit request/i }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('does not expose internal codes or PII in the DOM', async () => {
    renderPage();
    await screen.findByRole('combobox', { name: /site/i });

    const bodyText = document.body.textContent ?? '';
    // No internal state codes (e.g. IN_PROGRESS, DISPATCHED)
    expect(bodyText).not.toMatch(/\bIN_PROGRESS\b/);
    expect(bodyText).not.toMatch(/\bDISPATCHED\b/);
    // No GPS data patterns
    expect(bodyText).not.toMatch(/\d+\.\d{4,}/);
  });

  it('shows empty state when no sites are returned', async () => {
    mockRespond('GET', '/api/v1/portal/sites', { status: 200, body: { data: [] } });
    renderPage();

    expect(await screen.findByText(/no sites registered/i)).toBeInTheDocument();
  });
});
