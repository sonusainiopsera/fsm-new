/**
 * Component tests for ErasureConfirmDialog — confirmation gating, idempotency and 422.
 */

import React from 'react';
import { describe, it, expect, beforeAll, afterEach, afterAll, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  installHandlers, uninstallHandlers, resetHandlers, mockRespond,
} from '../../../mocks/handlers/index.js';
import { DensityProvider }        from '../../../density/DensityContext.js';
import { ToastProvider }          from '../../../components/index.js';
import { ErasureConfirmDialog }   from '../ErasureConfirmDialog.jsx';

const MOCK_DSAR = {
  id: 'dsar-001',
  requestType: 'ERASURE',
  subjectType: 'APP_USER',
  subjectId: 'user-101',
  state: 'VERIFIED',
};

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

describe('ErasureConfirmDialog', () => {
  it('confirm button is disabled before valid input', async () => {
    const onClose = vi.fn(), onSuccess = vi.fn();
    render(
      <Wrapper>
        <ErasureConfirmDialog open dsarRequest={MOCK_DSAR} onClose={onClose} onSuccess={onSuccess} />
      </Wrapper>
    );
    const btn = screen.getByRole('button', { name: /erase data/i });
    expect(btn).toBeDisabled();
  });

  it('confirm button is enabled after typing CONFIRM', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn(), onSuccess = vi.fn();
    render(
      <Wrapper>
        <ErasureConfirmDialog open dsarRequest={MOCK_DSAR} onClose={onClose} onSuccess={onSuccess} />
      </Wrapper>
    );
    const input = screen.getByLabelText(/type.*CONFIRM/i);
    await user.type(input, 'CONFIRM');
    const btn = screen.getByRole('button', { name: /erase data/i });
    expect(btn).not.toBeDisabled();
  });

  it('confirm button stays disabled on partial input', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn(), onSuccess = vi.fn();
    render(
      <Wrapper>
        <ErasureConfirmDialog open dsarRequest={MOCK_DSAR} onClose={onClose} onSuccess={onSuccess} />
      </Wrapper>
    );
    const input = screen.getByLabelText(/type.*CONFIRM/i);
    await user.type(input, 'CON');
    const btn = screen.getByRole('button', { name: /erase data/i });
    expect(btn).toBeDisabled();
  });

  it('calls onSuccess and closes on successful erasure', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn(), onSuccess = vi.fn();
    render(
      <Wrapper>
        <ErasureConfirmDialog open dsarRequest={MOCK_DSAR} onClose={onClose} onSuccess={onSuccess} />
      </Wrapper>
    );
    const input = screen.getByLabelText(/type.*CONFIRM/i);
    await user.type(input, 'CONFIRM');
    const btn = screen.getByRole('button', { name: /erase data/i });
    await user.click(btn);
    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalled();
  });

  it('shows 422 guard-refusal message without stack trace', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn(), onSuccess = vi.fn();
    mockRespond('POST', '/api/v1/privacy/subjects/APP_USER/user-101/erasure', {
      status: 422,
      body: {
        status: 422, code: 'GUARD_REFUSED',
        message: 'DSAR request must be in state VERIFIED to authorise erasure',
        fieldErrors: [], traceId: 'test-t',
      },
    });
    render(
      <Wrapper>
        <ErasureConfirmDialog open dsarRequest={MOCK_DSAR} onClose={onClose} onSuccess={onSuccess} />
      </Wrapper>
    );
    const input = screen.getByLabelText(/type.*CONFIRM/i);
    await user.type(input, 'CONFIRM');
    const btn = screen.getByRole('button', { name: /erase data/i });
    await user.click(btn);
    await waitFor(() => {
      expect(screen.getByText(/erasure refused/i)).toBeInTheDocument();
      expect(screen.getByText(/VERIFIED/i)).toBeInTheDocument();
    });
    // Must not show raw stack content
    expect(screen.queryByText(/at Object\./)).not.toBeInTheDocument();
  });

  it('states irreversibility', () => {
    const onClose = vi.fn(), onSuccess = vi.fn();
    render(
      <Wrapper>
        <ErasureConfirmDialog open dsarRequest={MOCK_DSAR} onClose={onClose} onSuccess={onSuccess} />
      </Wrapper>
    );
    expect(screen.getByText(/irreversible/i)).toBeInTheDocument();
    expect(screen.getByText(/non-identifying transaction records/i)).toBeInTheDocument();
  });
});
