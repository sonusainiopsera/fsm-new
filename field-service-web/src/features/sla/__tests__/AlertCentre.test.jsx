/**
 * AlertCentre — unit tests for sorting, empty state, and stale labelling.
 */

import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
} from '../../../mocks/handlers/index.js';
import { AlertCentre } from '../AlertCentre.jsx';

function makeQc() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
}

function renderAlertCentre(props = {}) {
  const qc = makeQc();
  return render(
    <QueryClientProvider client={qc}>
      <AlertCentre streamStatus="live" {...props} />
    </QueryClientProvider>,
  );
}

beforeAll(installHandlers);
afterEach(resetHandlers);
afterAll(uninstallHandlers);

describe('AlertCentre', () => {
  it('renders empty state when no alerts returned', async () => {
    mockRespond('GET', '/api/v1/sla/open-alerts', { status: 200, body: { data: [] } });
    renderAlertCentre();
    await waitFor(() => {
      expect(screen.getByText(/No open SLA alerts/i)).toBeInTheDocument();
    });
  });

  it('renders alert items from the API', async () => {
    renderAlertCentre();
    await waitFor(() => {
      expect(screen.getByText('WO-001')).toBeInTheDocument();
      expect(screen.getByText('WO-SLA-002')).toBeInTheDocument();
    });
  });

  it('shows stale note when streamStatus is stale', async () => {
    renderAlertCentre({ streamStatus: 'stale' });
    await waitFor(() => {
      expect(screen.getByText(/Data may be out of date/i)).toBeInTheDocument();
    });
  });

  it('does not show stale note when streamStatus is live', async () => {
    renderAlertCentre({ streamStatus: 'live' });
    await waitFor(() => {
      expect(screen.queryByText(/Data may be out of date/i)).not.toBeInTheDocument();
    });
  });

  describe('sort order', () => {
    it('lists breached alerts before at-risk alerts', async () => {
      // Default fixture has at-risk (wo-001) and breached (wo-sla-002)
      renderAlertCentre();
      await waitFor(() => {
        const items = screen.getAllByRole('listitem');
        // breached (WO-SLA-002) should appear first
        expect(items[0]).toHaveTextContent('WO-SLA-002');
        expect(items[1]).toHaveTextContent('WO-001');
      });
    });
  });

  it('shows error state when API fails', async () => {
    mockRespond('GET', '/api/v1/sla/open-alerts', { status: 503, body: { code: 'SERVICE_UNAVAILABLE' } });
    renderAlertCentre();
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/Could not load SLA alerts/i);
    });
  });

  it('shows alert count badge when alerts exist', async () => {
    renderAlertCentre();
    await waitFor(() => {
      expect(screen.getByLabelText(/2 open alerts/i)).toBeInTheDocument();
    });
  });
});
