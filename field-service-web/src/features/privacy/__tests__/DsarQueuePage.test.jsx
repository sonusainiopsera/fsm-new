/**
 * Component tests for DsarQueuePage — countdown, at-risk and overdue treatments.
 */

import React from 'react';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers, uninstallHandlers, resetHandlers, mockRespond, errorFixture,
} from '../../../mocks/handlers/index.js';
import { DensityProvider }  from '../../../density/DensityContext.js';
import { ToastProvider }    from '../../../components/index.js';
import { DsarQueuePage }    from '../DsarQueuePage.jsx';

function Wrapper({ children }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <DensityProvider>
        <ToastProvider>
          <MemoryRouter>{children}</MemoryRouter>
        </ToastProvider>
      </DensityProvider>
    </QueryClientProvider>
  );
}

beforeAll(() => installHandlers());
afterEach(() => resetHandlers());
afterAll(() => uninstallHandlers());

describe('DsarQueuePage', () => {
  it('renders DSAR list from mock API', async () => {
    render(<Wrapper><DsarQueuePage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText('Access')).toBeInTheDocument();
    });
    expect(screen.getByText('Erasure')).toBeInTheDocument();
  });

  it('shows at-risk treatment for at-risk countdown', async () => {
    render(<Wrapper><DsarQueuePage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('Access')).toBeInTheDocument());
    const atRiskEl = screen.getByLabelText(/at risk/i);
    expect(atRiskEl).toBeInTheDocument();
  });

  it('shows overdue treatment for negative remaining days', async () => {
    render(<Wrapper><DsarQueuePage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('Access')).toBeInTheDocument());
    expect(screen.getByText(/overdue/i)).toBeInTheDocument();
  });

  it('does NOT show at-risk treatment for normal countdown', async () => {
    render(<Wrapper><DsarQueuePage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('Erasure')).toBeInTheDocument());
    // dsar-002 has 19 days remaining and atRisk: false — no at-risk indicator on that row
    const allAtRisk = screen.queryAllByLabelText(/at risk/i);
    // Only 1 at-risk entry (dsar-001), not the normal one
    expect(allAtRisk.length).toBe(1);
  });

  it('shows PermissionDenied on 403', async () => {
    mockRespond('GET', '/api/v1/privacy/dsar-requests', errorFixture(403));
    render(<Wrapper><DsarQueuePage roles={[]} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/access restricted/i)).toBeInTheDocument();
    });
  });

  it('shows empty state when no requests', async () => {
    mockRespond('GET', '/api/v1/privacy/dsar-requests', {
      status: 200,
      body: { data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }, _links: {} },
    });
    render(<Wrapper><DsarQueuePage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/no dsar requests/i)).toBeInTheDocument();
    });
  });
});
