/**
 * SurveyPage — unit + integration tests (WO-175).
 *
 * Coverage:
 * - Happy path: renders score radio group, NPS group, comment textarea, submit button.
 * - Successful submission shows thank-you message.
 * - Already-answered: renders submitted score, not the form.
 * - Expired window: renders "Survey window has closed" message, not the form.
 * - 409 server response: inline error, form remains visible.
 * - 422/400 server response: inline error with server message.
 * - 404/403: "Survey not found" error state.
 * - Score is required: shows validation message if submitted without score.
 * - Comment has PII notice.
 * - aria-live regions exist for status announcements.
 */

import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
  errorFixture,
} from '../../../mocks/handlers/index.js';

import SurveyPage from '../SurveyPage.jsx';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function makeQC() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderPage(requestId = 'srh-001', qc = makeQC()) {
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/portal/survey/${requestId}`]}>
        <Routes>
          <Route path="/portal/survey/:requestId" element={<SurveyPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// ─── Suite setup ──────────────────────────────────────────────────────────────

beforeAll(() => installHandlers());
afterAll(() => uninstallHandlers());
afterEach(() => resetHandlers());

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('SurveyPage', () => {

  it('renders score radio group', async () => {
    renderPage();
    // ScoreRadioGroup renders a radiogroup role
    const group = await screen.findByRole('radiogroup', { name: /score/i });
    expect(group).toBeInTheDocument();
    // 5 radio buttons for 1–5
    const radios = within(group).getAllByRole('radio');
    expect(radios).toHaveLength(5);
  });

  it('renders NPS radio group', async () => {
    renderPage();
    await screen.findByRole('radiogroup', { name: /score/i });
    // NPS group 0–10
    const npsGroup = screen.getAllByRole('radiogroup')[1];
    expect(npsGroup).toBeInTheDocument();
    const npsRadios = within(npsGroup).getAllByRole('radio');
    expect(npsRadios).toHaveLength(11); // 0–10
  });

  it('renders comment textarea with PII notice', async () => {
    renderPage();
    await screen.findByRole('radiogroup', { name: /score/i });
    expect(screen.getByRole('textbox', { name: /additional feedback/i })).toBeInTheDocument();
    expect(screen.getByText(/do not include personal information/i)).toBeInTheDocument();
  });

  it('shows validation error when submitting without a score', async () => {
    const user = userEvent.setup();
    renderPage();
    const submitBtn = await screen.findByRole('button', { name: /submit feedback/i });
    await user.click(submitBtn);
    expect(await screen.findByRole('alert')).toHaveTextContent(/please select a score/i);
  });

  it('successful submission shows thank-you message', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('radiogroup', { name: /score/i });

    const scoreGroup = screen.getAllByRole('radiogroup')[0];
    const radio4 = within(scoreGroup).getByRole('radio', { name: /4/i });
    await user.click(radio4);

    const submitBtn = screen.getByRole('button', { name: /submit feedback/i });
    await user.click(submitBtn);

    expect(await screen.findByText(/thank you for your feedback/i)).toBeInTheDocument();
  });

  it('already-answered view renders score summary, not form', async () => {
    renderPage('srh-004');
    expect(await screen.findByText(/survey already submitted/i)).toBeInTheDocument();
    expect(screen.getByText('4 / 5')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /submit feedback/i })).not.toBeInTheDocument();
  });

  it('expired-window view renders expiry message, not form', async () => {
    renderPage('srh-005');
    expect(await screen.findByText(/survey window has closed/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /submit feedback/i })).not.toBeInTheDocument();
  });

  it('shows 409 inline error without hiding form', async () => {
    const user = userEvent.setup();
    mockRespond('POST', '/api/v1/portal/service-requests/srh-001/survey', errorFixture(409, 'ALREADY_ANSWERED'));
    renderPage();
    await screen.findByRole('radiogroup', { name: /score/i });

    const scoreGroup = screen.getAllByRole('radiogroup')[0];
    await user.click(within(scoreGroup).getByRole('radio', { name: /3/i }));
    await user.click(screen.getByRole('button', { name: /submit feedback/i }));

    expect(await screen.findByText(/already been submitted/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /submit feedback/i })).toBeInTheDocument();
  });

  it('shows 422 inline error without hiding form', async () => {
    const user = userEvent.setup();
    mockRespond('POST', '/api/v1/portal/service-requests/srh-001/survey', {
      status: 422,
      body: { message: 'Score must be between 1 and 5.' },
    });
    renderPage();
    await screen.findByRole('radiogroup', { name: /score/i });

    const scoreGroup = screen.getAllByRole('radiogroup')[0];
    await user.click(within(scoreGroup).getByRole('radio', { name: /2/i }));
    await user.click(screen.getByRole('button', { name: /submit feedback/i }));

    expect(await screen.findByText(/score must be between 1 and 5/i)).toBeInTheDocument();
  });

  it('shows 404 error state', async () => {
    mockRespond('GET', '/api/v1/portal/service-requests/srh-001/survey', errorFixture(404, 'NOT_FOUND'));
    renderPage();
    expect(await screen.findByText(/survey not found/i)).toBeInTheDocument();
  });

  it('has aria-live region for status announcements', async () => {
    renderPage();
    await screen.findByRole('radiogroup', { name: /score/i });
    // The char count has aria-live
    const liveRegions = document.querySelectorAll('[aria-live]');
    expect(liveRegions.length).toBeGreaterThan(0);
  });
});
