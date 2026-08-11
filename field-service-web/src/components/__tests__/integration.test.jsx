/**
 * System integration tests: composed screen using the mock transport,
 * driving success, 403, 409/422 and stale-data degraded paths.
 */
import React, { useState, useEffect } from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, beforeEach } from 'vitest';

import { DensityProvider } from '../../density/DensityContext.js';
import { ToastProvider } from '../Toast/ToastProvider.jsx';
import { DataTable } from '../DataTable/DataTable.jsx';
import {
  LoadingState,
  DegradedState,
  PermissionDeniedState,
  ErrorState,
  EmptyState,
} from '../StateSurface/StateSurface.jsx';
import { createMockTransport } from '../../mocks/mockTransport.js';

const COLS = [
  { key: 'reference', header: 'Reference' },
  { key: 'priority', header: 'Priority' },
  { key: 'state', header: 'State' },
];

function ScreenUnderTest({ transport }) {
  const [status, setStatus] = useState('idle');
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [isStale, setIsStale] = useState(false);

  useEffect(() => {
    setStatus('loading');
    transport.fetch('dispatcher')
      .then((res) => {
        setData(res.data.workOrders);
        setIsStale(res.stale);
        setStatus('success');
      })
      .catch((err) => {
        setError(err);
        setStatus('error');
      });
  }, [transport]);

  if (status === 'loading' || status === 'idle') return <LoadingState />;
  if (status === 'error') {
    if (error?.status === 403) return <PermissionDeniedState />;
    if (error?.status === 409 || error?.status === 422) return <ErrorState description={error.message} />;
    return <ErrorState />;
  }

  return (
    <div>
      {isStale && <DegradedState />}
      <DataTable
        columns={COLS}
        data={data ?? []}
        rowKey={(r) => r.id}
        emptyState={<EmptyState />}
      />
    </div>
  );
}

function Wrapper({ transport }) {
  return (
    <ToastProvider>
      <DensityProvider>
        <ScreenUnderTest transport={transport} />
      </DensityProvider>
    </ToastProvider>
  );
}

beforeEach(() => {
  if (typeof ResizeObserver === 'undefined') {
    global.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

describe('Integration: mock transport scenarios', () => {
  it('success path: shows loading then renders data rows', async () => {
    const t = createMockTransport({ latencyMs: 10 });
    render(<Wrapper transport={t} />);
    expect(screen.getByRole('status')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('WO-2024-0001')).toBeInTheDocument());
  });

  it('403 path: shows LoadingState then PermissionDeniedState', async () => {
    const t = createMockTransport({ latencyMs: 10, errorCode: 403 });
    render(<Wrapper transport={t} />);
    await waitFor(() =>
      expect(screen.getByText('Access restricted')).toBeInTheDocument()
    );
  });

  it('422 path: shows ErrorState with message', async () => {
    const t = createMockTransport({ latencyMs: 10, errorCode: 422 });
    render(<Wrapper transport={t} />);
    await waitFor(() =>
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    );
  });

  it('stale path: shows DegradedState banner alongside data', async () => {
    const t = createMockTransport({ latencyMs: 10, stale: true });
    render(<Wrapper transport={t} />);
    await waitFor(() =>
      expect(screen.getByText('Showing stale data')).toBeInTheDocument()
    );
    expect(screen.getByText('WO-2024-0001')).toBeInTheDocument();
  });
});
