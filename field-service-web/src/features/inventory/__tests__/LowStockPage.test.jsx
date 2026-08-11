/**
 * LowStockPage component tests.
 *
 * Covers: alerts render with text+icon+shape indicators, empty state,
 * degraded state on stale asOf, error state.
 */

import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { DensityProvider } from '../../../density/DensityContext.js';
import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
} from '../../../mocks/handlers/index.js';
import { LowStockPage } from '../LowStockPage.jsx';

function Wrapper({ children }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: 0 }, mutations: { retry: 0 } } });
  return (
    <QueryClientProvider client={qc}>
      <DensityProvider>
        {children}
      </DensityProvider>
    </QueryClientProvider>
  );
}

beforeAll(() => {
  installHandlers();
  if (typeof global.ResizeObserver === 'undefined') {
    global.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});
afterEach(() => resetHandlers());
afterAll(() => uninstallHandlers());

describe('LowStockPage', () => {
  it('renders LOW and OUT alerts from the API', async () => {
    render(<Wrapper><LowStockPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('BRK-1040')).toBeInTheDocument();
      expect(screen.getByText('HVA-0055')).toBeInTheDocument();
    });
  });

  it('renders LOW status indicator with text label and shape icon', async () => {
    render(<Wrapper><LowStockPage /></Wrapper>);

    await waitFor(() => {
      const lowEl = screen.getAllByRole('img', { name: /low stock/i });
      expect(lowEl.length).toBeGreaterThan(0);
    });
  });

  it('renders OUT (Stockout) status indicator with text label and distinct shape icon', async () => {
    render(<Wrapper><LowStockPage /></Wrapper>);

    await waitFor(() => {
      const outEl = screen.getByRole('img', { name: /out of stock/i });
      expect(outEl).toBeInTheDocument();
    });
  });

  it('renders empty state when no alerts', async () => {
    mockRespond('GET', '/api/v1/inventory/alerts', {
      status: 200,
      body: {
        data: [],
        page: { number: 0, size: 50, totalElements: 0, totalPages: 0, estimated: false },
        _links: {},
        asOf: '2026-08-11T10:00:00Z',
      },
    });

    render(<Wrapper><LowStockPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText(/no active alerts/i)).toBeInTheDocument();
    });
  });

  it('renders degraded state when asOf exceeds 60s budget', async () => {
    const staleAsOf = new Date(Date.now() - 120_000).toISOString();
    mockRespond('GET', '/api/v1/inventory/alerts', {
      status: 200,
      body: {
        data: [
          {
            id: 'alert-stale', partId: 'p-1', partNumber: 'X-001',
            partDescription: 'Test Part', locationId: 'loc-1',
            locationName: 'WH', quantityOnHand: 0, reorderPoint: 5,
            stockStatus: 'OUT', raisedAt: staleAsOf, asOf: staleAsOf,
          },
        ],
        page: { number: 0, size: 50, totalElements: 1, totalPages: 1, estimated: false },
        _links: {},
        asOf: staleAsOf,
      },
    });

    render(<Wrapper><LowStockPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText(/stale/i)).toBeInTheDocument();
    });
  });

  it('renders error state on API failure', async () => {
    mockRespond('GET', '/api/v1/inventory/alerts', {
      status: 500,
      body: { status: 500, code: 'INTERNAL_ERROR', message: 'Server error.', fieldErrors: [], traceId: 'trace-err' },
    });

    render(<Wrapper><LowStockPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText(/could not load stock alerts/i)).toBeInTheDocument();
    });
  });
});
