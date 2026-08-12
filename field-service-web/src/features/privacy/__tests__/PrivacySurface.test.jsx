/**
 * Integration tests for the Privacy surface — authorisation, route rendering and
 * permission-denied on non-privacy roles.
 */

import React from 'react';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers, uninstallHandlers, resetHandlers, mockRespond, errorFixture,
} from '../../../mocks/handlers/index.js';
import { DensityProvider } from '../../../density/DensityContext.js';
import { ToastProvider }   from '../../../components/index.js';
import PrivacySurface      from '../../../surfaces/privacy/index.jsx';

function Wrapper({ children, initialPath = '/privacy/classifications' }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <DensityProvider>
        <ToastProvider>
          <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
              <Route path="/privacy/*" element={children} />
            </Routes>
          </MemoryRouter>
        </ToastProvider>
      </DensityProvider>
    </QueryClientProvider>
  );
}

beforeAll(() => installHandlers());
afterEach(() => resetHandlers());
afterAll(() => uninstallHandlers());

describe('PrivacySurface authorisation', () => {
  it('renders classification page for PRIVACY_ADMIN', async () => {
    render(<Wrapper><PrivacySurface roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/classification registry/i)).toBeInTheDocument();
    });
  });

  it('renders classification page for ADMIN', async () => {
    render(<Wrapper><PrivacySurface roles={['ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/classification registry/i)).toBeInTheDocument();
    });
  });

  it('renders permission-denied for DISPATCHER role', () => {
    render(<Wrapper><PrivacySurface roles={['DISPATCHER']} /></Wrapper>);
    expect(screen.getByText(/access restricted/i)).toBeInTheDocument();
  });

  it('renders permission-denied for TECHNICIAN role', () => {
    render(<Wrapper><PrivacySurface roles={['TECHNICIAN']} /></Wrapper>);
    expect(screen.getByText(/access restricted/i)).toBeInTheDocument();
  });

  it('renders permission-denied for MANAGER role', () => {
    render(<Wrapper><PrivacySurface roles={['MANAGER']} /></Wrapper>);
    expect(screen.getByText(/access restricted/i)).toBeInTheDocument();
  });

  it('renders permission-denied for empty roles', () => {
    render(<Wrapper><PrivacySurface roles={[]} /></Wrapper>);
    expect(screen.getByText(/access restricted/i)).toBeInTheDocument();
  });

  it('renders DSAR queue for PRIVACY_ADMIN', async () => {
    render(<Wrapper initialPath="/privacy/dsar"><PrivacySurface roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/dsar queue/i)).toBeInTheDocument();
    });
  });

  it('renders retention schedule for PRIVACY_ADMIN', async () => {
    render(<Wrapper initialPath="/privacy/retention"><PrivacySurface roles={['PRIVACY_ADMIN']} /></Wrapper>);
    await waitFor(() => {
      expect(screen.getByText(/retention schedule/i)).toBeInTheDocument();
    });
  });
});

describe('PrivacySurface navigation', () => {
  it('shows subnav links', async () => {
    render(<Wrapper><PrivacySurface roles={['PRIVACY_ADMIN']} /></Wrapper>);
    expect(screen.getByText('Classifications')).toBeInTheDocument();
    expect(screen.getByText('Retention schedule')).toBeInTheDocument();
    expect(screen.getByText('DSAR queue')).toBeInTheDocument();
  });
});
