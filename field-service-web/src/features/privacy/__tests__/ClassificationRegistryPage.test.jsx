/**
 * Component tests for ClassificationRegistryPage.
 */

import React from 'react';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers, uninstallHandlers, resetHandlers, mockRespond, errorFixture,
} from '../../../mocks/handlers/index.js';
import { DensityProvider }  from '../../../density/DensityContext.js';
import { ToastProvider }    from '../../../components/index.js';
import { ClassificationRegistryPage } from '../ClassificationRegistryPage.jsx';

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

describe('ClassificationRegistryPage', () => {
  it('renders classification rows from mock API', async () => {
    render(<Wrapper><ClassificationRegistryPage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText('AppUser')).toBeInTheDocument();
    });
    expect(screen.getByText('email')).toBeInTheDocument();
    expect(screen.getByText('Confidential')).toBeInTheDocument();
  });

  it('shows Edit affordance for PRIVACY_ADMIN role', async () => {
    render(<Wrapper><ClassificationRegistryPage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('AppUser')).toBeInTheDocument());
    const editButtons = screen.getAllByRole('button', { name: /edit/i });
    expect(editButtons.length).toBeGreaterThan(0);
  });

  it('shows PermissionDenied on 403', async () => {
    mockRespond('GET', '/api/v1/privacy/classifications', errorFixture(403));
    render(<Wrapper><ClassificationRegistryPage roles={[]} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/access restricted/i)).toBeInTheDocument();
    });
  });

  it('shows Error state on 500', async () => {
    mockRespond('GET', '/api/v1/privacy/classifications',
      { status: 500, body: { code: 'INTERNAL_ERROR', message: 'Server error' } });
    render(<Wrapper><ClassificationRegistryPage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
    });
  });

  it('shows conflict notice on 409 during update', async () => {
    const user = userEvent.setup();
    render(<Wrapper><ClassificationRegistryPage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => expect(screen.getByText('AppUser')).toBeInTheDocument());

    // Open edit
    const editBtns = screen.getAllByRole('button', { name: /edit/i });
    await user.click(editBtns[0]);
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());

    // Mock 409 conflict
    mockRespond('PUT', '/api/v1/privacy/classifications/cls-001',
      { status: 409, body: { code: 'CONFLICT', message: 'Stale version', fieldErrors: [], traceId: 't' } });

    const saveBtn = screen.getByRole('button', { name: /save/i });
    await user.click(saveBtn);

    await waitFor(() => {
      expect(screen.getByText(/another user updated/i)).toBeInTheDocument();
    });
  });

  it('shows empty state when no rows', async () => {
    mockRespond('GET', '/api/v1/privacy/classifications', {
      status: 200,
      body: { data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }, _links: {} },
    });
    render(<Wrapper><ClassificationRegistryPage roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/no classification rows/i)).toBeInTheDocument();
    });
  });
});
