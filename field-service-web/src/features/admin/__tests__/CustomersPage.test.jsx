/**
 * Component tests for CustomersPage.
 *
 * Uses MSW mock handlers (installHandlers) to drive every documented API outcome.
 */

import React from 'react';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { installHandlers, uninstallHandlers, resetHandlers, mockRespond, errorFixture } from '../../../mocks/handlers/index.js';
import { DensityProvider }  from '../../../density/DensityContext.js';
import { ToastProvider }    from '../../../components/index.js';
import { CustomersPage }    from '../CustomersPage.jsx';

function Wrapper({ children }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <DensityProvider>
        <ToastProvider>
          <MemoryRouter>
            {children}
          </MemoryRouter>
        </ToastProvider>
      </DensityProvider>
    </QueryClientProvider>
  );
}

beforeAll(() => installHandlers());
afterEach(() => resetHandlers());
afterAll(() => uninstallHandlers());

describe('CustomersPage', () => {
  it('renders customer list from mock API', async () => {
    render(<Wrapper><CustomersPage roles={['ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    });
    expect(screen.getByText('Beta Industries')).toBeInTheDocument();
  });

  it('shows Add Customer button for ADMIN role', async () => {
    render(<Wrapper><CustomersPage roles={['ADMIN']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('Acme Corp')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /add customer/i })).toBeInTheDocument();
  });

  it('hides Add Customer button for DISPATCHER role', async () => {
    render(<Wrapper><CustomersPage roles={['DISPATCHER']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('Acme Corp')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /add customer/i })).not.toBeInTheDocument();
  });

  it('renders empty state when list is empty', async () => {
    mockRespond('GET', '/api/v1/customers', {
      status: 200,
      body: {
        data: [],
        page: { number: 0, size: 20, totalElements: 0, totalPages: 0, estimated: false },
        _links: {},
      },
    });
    render(<Wrapper><CustomersPage roles={['ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/no customers/i)).toBeInTheDocument();
    });
  });

  it('renders error state on 500 server error', async () => {
    mockRespond('GET', '/api/v1/customers', { status: 500, body: { code: 'INTERNAL', message: 'Server error', fieldErrors: [], traceId: 'err-001' } });
    render(<Wrapper><CustomersPage roles={['ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/server error/i)).toBeInTheDocument();
    });
  });

  it('renders inline field errors on 400 validation failure', async () => {
    // First load succeeds
    render(<Wrapper><CustomersPage roles={['ADMIN']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('Acme Corp')).toBeInTheDocument());

    // Click "Add Customer" to open the drawer
    await userEvent.click(screen.getByRole('button', { name: /add customer/i }));

    // Override POST to return 400 with field errors
    mockRespond('POST', '/api/v1/customers', {
      ...errorFixture(400, 'VALIDATION_FAILED', 'Validation failed', [
        { field: 'name', message: 'must not be blank' },
        { field: 'contactEmail', message: 'must be a valid email address' },
      ]),
    });

    // Submit the empty form
    const submitBtn = screen.getByRole('button', { name: /create customer/i });
    await userEvent.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByText('must not be blank')).toBeInTheDocument();
      expect(screen.getByText('must be a valid email address')).toBeInTheDocument();
    });
  });

  it('renders 403 permission denied for non-admin', async () => {
    mockRespond('GET', '/api/v1/customers', errorFixture(403, 'FORBIDDEN', 'Forbidden'));
    render(<Wrapper><CustomersPage roles={['TECHNICIAN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/do not have permission/i)).toBeInTheDocument();
    });
  });
});
