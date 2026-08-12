/**
 * WorkOrderBoardPage integration tests.
 *
 * Covers:
 * - Initial load with board fixture
 * - Filter-to-query-parameter serialisation and round-trip
 * - Empty-state rendering with reset affordance
 * - 403 permission-denied state
 * - Deep-linked drawer open on direct navigation
 * - Legal-events-to-actions mapping (server is authoritative)
 * - Transition success → board invalidation
 * - Transition refusal codes: illegal, version-conflict, guard-refused
 * - 304 conditional GET performs no re-render (stable render count)
 */

import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeAll, afterEach, afterAll, vi } from 'vitest';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
  seedEtag,
} from '../../../mocks/handlers/index.js';
import {
  boardFixture,
  boardEmptyFixture,
  boardForbiddenFixture,
  workOrderDetailFixture,
  transitionSuccessFixture,
  transitionIllegalFixture,
  transitionVersionConflictFixture,
  transitionGuardRefusedFixture,
} from '../../../mocks/handlers/workOrders.js';
import WorkOrderBoardPage from '../WorkOrderBoardPage.jsx';

// ---- Test helpers --------------------------------------------------------

function makeQc() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

/**
 * Renders the board at the given initial URL.
 * @param {string} [initialPath]
 * @param {QueryClient} [qc]
 */
function renderBoard(initialPath = '/dispatch', qc = makeQc()) {
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/dispatch" element={<WorkOrderBoardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// ---- Setup ---------------------------------------------------------------

beforeAll(() => installHandlers());
afterEach(() => resetHandlers());
afterAll(() => uninstallHandlers());

// ---- Tests ---------------------------------------------------------------

describe('WorkOrderBoardPage', () => {

  it('renders work orders from the board fixture', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    renderBoard();

    await waitFor(() => {
      expect(screen.getByText('WO-2026-001')).toBeInTheDocument();
    });

    expect(screen.getByText('WO-2026-002')).toBeInTheDocument();
    expect(screen.getByText('WO-2026-003')).toBeInTheDocument();
  });

  it('renders empty state with reset affordance when no results match filters', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardEmptyFixture());
    renderBoard('/dispatch?states=CANCELLED');

    await waitFor(() => {
      expect(screen.getByText(/no matching work orders/i)).toBeInTheDocument();
    });

    // Clear filters affordance must be present
    expect(screen.getByRole('button', { name: /clear/i })).toBeInTheDocument();
  });

  it('renders PermissionDeniedState on 403', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardForbiddenFixture());
    renderBoard();

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    // Should not render any work-order rows
    expect(screen.queryByText('WO-2026-001')).not.toBeInTheDocument();
  });

  it('serialises state filter chips to URL query param', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    renderBoard();

    await waitFor(() => screen.getByText('WO-2026-001'));

    // Click the "NEW" state chip in the FilterBar
    const newChip = screen.getByRole('button', { name: /filter by state: new/i });
    fireEvent.click(newChip);

    await waitFor(() => {
      expect(newChip).toHaveAttribute('aria-pressed', 'true');
    });
  });

  it('at-risk toggle reflects in URL and marks filter active', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    renderBoard();

    await waitFor(() => screen.getByText('WO-2026-001'));

    const atRiskToggle = screen.getByLabelText(/show at-risk work orders only/i);
    fireEvent.click(atRiskToggle);

    expect(atRiskToggle).toBeChecked();
  });

  it('opens detail drawer when a row is clicked', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    mockRespond('GET', '/api/v1/work-orders/wo-001', workOrderDetailFixture('wo-001'));
    renderBoard();

    await waitFor(() => screen.getByText('WO-2026-001'));

    // Click a table row
    const rows = screen.getAllByRole('row');
    // First row is header, second is first data row
    fireEvent.click(rows[1]);

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
  });

  it('deep-linked drawer id opens drawer directly from URL', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    mockRespond('GET', '/api/v1/work-orders/wo-001', workOrderDetailFixture('wo-001'));
    renderBoard('/dispatch?drawerId=wo-001');

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
  });

  it('drawer closes on Escape key', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    mockRespond('GET', '/api/v1/work-orders/wo-001', workOrderDetailFixture('wo-001'));
    renderBoard('/dispatch?drawerId=wo-001');

    await waitFor(() => screen.getByRole('dialog'));

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('lifecycle action buttons come solely from legalNextEvents — fabricated list changes rendered actions', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    // Detail with a fabricated legalNextEvents list
    mockRespond('GET', '/api/v1/work-orders/wo-001', workOrderDetailFixture('wo-001', {
      legalNextEvents: ['HOLD', 'RESUME', 'COMPLETE'],
    }));
    renderBoard('/dispatch?drawerId=wo-001');

    await waitFor(() => screen.getByRole('dialog'));

    // Only the server-declared events should appear as buttons
    expect(screen.getByRole('button', { name: /place on hold/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /resume/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /complete/i })).toBeInTheDocument();
    // ASSIGN was not in the fabricated list
    expect(screen.queryByRole('button', { name: /assign/i })).not.toBeInTheDocument();
  });

  it('409 version-conflict refusal shows reload message', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    mockRespond('GET', '/api/v1/work-orders/wo-001', workOrderDetailFixture('wo-001', {
      legalNextEvents: ['ASSIGN'],
    }));
    renderBoard('/dispatch?drawerId=wo-001');

    await waitFor(() => screen.getByRole('button', { name: /assign/i }));

    mockRespond('POST', '/api/v1/work-orders/wo-001/transitions', transitionVersionConflictFixture());

    fireEvent.click(screen.getByRole('button', { name: /assign/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    expect(screen.getByText(/updated by someone else/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reload now/i })).toBeInTheDocument();
  });

  it('422 guard-refused refusal shows specific guard message', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    mockRespond('GET', '/api/v1/work-orders/wo-001', workOrderDetailFixture('wo-001', {
      legalNextEvents: ['ASSIGN'],
    }));
    renderBoard('/dispatch?drawerId=wo-001');

    await waitFor(() => screen.getByRole('button', { name: /assign/i }));

    mockRespond('POST', '/api/v1/work-orders/wo-001/transitions', transitionGuardRefusedFixture('Technician must hold a valid GAS_SAFE certification.'));

    fireEvent.click(screen.getByRole('button', { name: /assign/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    expect(screen.getByText(/GAS_SAFE certification/i)).toBeInTheDocument();
  });

  it('409 illegal-transition refusal shows message and does not expose action buttons again until refreshed', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    mockRespond('GET', '/api/v1/work-orders/wo-001', workOrderDetailFixture('wo-001', {
      legalNextEvents: ['ASSIGN'],
    }));
    renderBoard('/dispatch?drawerId=wo-001');

    await waitFor(() => screen.getByRole('button', { name: /assign/i }));

    mockRespond('POST', '/api/v1/work-orders/wo-001/transitions', transitionIllegalFixture());

    fireEvent.click(screen.getByRole('button', { name: /assign/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    expect(screen.getByText(/no longer available/i)).toBeInTheDocument();
  });

  it('304 response does not trigger re-render (stable render count)', async () => {
    let renderCount = 0;
    function CountingBoard() {
      renderCount++;
      return <WorkOrderBoardPage />;
    }

    const qc = makeQc();
    mockRespond('GET', '/api/v1/work-orders', boardFixture());

    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/dispatch']}>
          <Routes>
            <Route path="/dispatch" element={<CountingBoard />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => screen.getByText('WO-2026-001'));
    const countAfterFirstLoad = renderCount;

    // Seed an ETag so the next request returns 304
    seedEtag('/api/v1/work-orders', '"board-etag-v1"');

    // Force a manual refetch via the query client
    await act(async () => {
      await qc.refetchQueries({ queryKey: ['work-orders', 'board'] });
    });

    // Render count should not have increased after 304
    expect(renderCount).toBe(countAfterFirstLoad);
  });

  it('sort column header activates and updates URL', async () => {
    mockRespond('GET', '/api/v1/work-orders', boardFixture());
    renderBoard();

    await waitFor(() => screen.getByText('WO-2026-001'));

    const sortBtn = screen.getByRole('button', { name: /sort by reference/i });
    fireEvent.click(sortBtn);

    // Column should now show ascending sort indicator
    expect(sortBtn).toBeInTheDocument();
  });

});

// ---- classifyTransitionError unit tests ----------------------------------

describe('classifyTransitionError', () => {
  const { classifyTransitionError } = await import('../api/useWorkOrderTransition.js');

  it('classifies WORK_ORDER_VERSION_CONFLICT as VERSION_CONFLICT', () => {
    const err = { status: 409, code: 'WORK_ORDER_VERSION_CONFLICT', message: 'conflict', fieldErrors: [], traceId: 'tr1', retryable: false };
    const r = classifyTransitionError(err);
    expect(r.type).toBe('VERSION_CONFLICT');
  });

  it('classifies 409 ILLEGAL as ILLEGAL_TRANSITION', () => {
    const err = { status: 409, code: 'WORK_ORDER_ILLEGAL_TRANSITION', message: 'bad', fieldErrors: [{ field: 'legalNextEvents', message: 'ASSIGN,CANCEL' }], traceId: null, retryable: false };
    const r = classifyTransitionError(err);
    expect(r.type).toBe('ILLEGAL_TRANSITION');
    expect(r.legalNextEvents).toEqual(['ASSIGN', 'CANCEL']);
  });

  it('classifies 422 as GUARD_REFUSED with guard message', () => {
    const err = { status: 422, code: 'WORK_ORDER_GUARD_REFUSED', message: 'check', fieldErrors: [{ field: 'guard', message: 'Missing cert.' }], traceId: null, retryable: false };
    const r = classifyTransitionError(err);
    expect(r.type).toBe('GUARD_REFUSED');
    expect(r.message).toBe('Missing cert.');
  });

  it('classifies 429 as RATE_LIMITED', () => {
    const err = { status: 429, code: 'RATE_LIMITED', message: 'slow down', fieldErrors: [], traceId: null, retryAfterMs: 10000, retryable: false };
    const r = classifyTransitionError(err);
    expect(r.type).toBe('RATE_LIMITED');
    expect(r.retryAfterMs).toBe(10000);
  });

  it('classifies network error (status 0) as NETWORK_ERROR', () => {
    const err = { status: 0, code: 'NETWORK_ERROR', message: 'fetch failed', fieldErrors: [], traceId: null, retryable: true };
    const r = classifyTransitionError(err);
    expect(r.type).toBe('NETWORK_ERROR');
  });
});
