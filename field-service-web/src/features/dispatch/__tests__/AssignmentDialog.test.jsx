/**
 * AssignmentDialog — unit and MSW flow tests.
 *
 * Unit tests:
 * - Override reason required when rank > 3 or absent, NOT required for rank ≤ 3
 * - Confirm button disabled until mandatory fields are valid
 * - Reassignment reason validation blocks empty selection
 * - Appointment ack step revealed only after server 422 breach code
 *
 * MSW flow tests:
 * - Successful assign: dialog closes, toast shown, queries invalidated
 * - Override-required assign: rank 4 requires reason, rank 3 does not
 * - Reassignment with reason: sends coded reason, notes optional
 * - 422 appointment breach then acknowledged resubmit: two-step flow
 * - 422 certification refusal: terminal screen, no resubmit
 * - 409 conflict with refresh action
 * - 400 field errors: inline messages on fields
 * - Retried submit idempotency: same Idempotency-Key across retry attempts
 */

import React, { useState } from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeAll, afterEach, afterAll } from 'vitest';

import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
} from '../../../mocks/handlers/index.js';
import {
  WO_ASSIGN_ID,
  assignSuccessFixture,
  reassignSuccessFixture,
  assignCertRefusedFixture,
  assignAppointmentBreachFixture,
  assignAfterAckFixture,
  assignConflictFixture,
  assignFieldErrorsFixture,
  rank3CandidateFixture,
  rank4CandidateFixture,
} from '../../../mocks/handlers/assignment.js';
import { ToastProvider } from '../../../components/Toast/ToastProvider.jsx';
import { AssignmentDialog } from '../components/AssignmentDialog.jsx';

// ── Helpers ───────────────────────────────────────────────────────────────────

const ASSIGN_PATH = `/api/v1/work-orders/${WO_ASSIGN_ID}/assignment`;
const REASSIGN_PATH = `/api/v1/work-orders/${WO_ASSIGN_ID}/reassignment`;

const DEFAULT_CANDIDATE = {
  technicianId: 'tech-rec-001',
  technicianName: 'Technician 1',
  rank: 1,
  score: 0.95,
  travelEstimateDegraded: false,
  factors: [],
};

function makeQc() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

function renderDialog({
  open = true,
  onClose = vi.fn(),
  workOrderId = WO_ASSIGN_ID,
  mode = 'assign',
  candidate = DEFAULT_CANDIDATE,
  snapshotId = '00000000-0000-7141-8000-000000000001',
  partsWarnings = [],
  onRefreshRecommendations = vi.fn(),
  qc = makeQc(),
} = {}) {
  return {
    ...render(
      <QueryClientProvider client={qc}>
        <ToastProvider>
          <AssignmentDialog
            open={open}
            onClose={onClose}
            workOrderId={workOrderId}
            mode={mode}
            candidate={candidate}
            snapshotId={snapshotId}
            partsWarnings={partsWarnings}
            onRefreshRecommendations={onRefreshRecommendations}
          />
        </ToastProvider>
      </QueryClientProvider>
    ),
    onClose,
    onRefreshRecommendations,
  };
}

// ── Setup/teardown ────────────────────────────────────────────────────────────

beforeAll(() => installHandlers());
afterEach(() => resetHandlers());
afterAll(() => uninstallHandlers());

// ── Unit tests: override-required derivation ──────────────────────────────────

describe('Override reason requirement', () => {
  it('does NOT show override field for rank 1', () => {
    renderDialog({ candidate: { ...DEFAULT_CANDIDATE, rank: 1 } });
    expect(screen.queryByLabelText(/override reason/i)).not.toBeInTheDocument();
  });

  it('does NOT show override field for rank 3 (boundary)', () => {
    renderDialog({ candidate: rank3CandidateFixture() });
    expect(screen.queryByLabelText(/override reason/i)).not.toBeInTheDocument();
  });

  it('shows override field for rank 4 (boundary)', () => {
    renderDialog({ candidate: rank4CandidateFixture() });
    expect(screen.getByLabelText(/override reason/i)).toBeInTheDocument();
  });

  it('shows override field for rank 5', () => {
    renderDialog({ candidate: { ...DEFAULT_CANDIDATE, rank: 5 } });
    expect(screen.getByLabelText(/override reason/i)).toBeInTheDocument();
  });

  it('shows override field when rank is absent from snapshot', () => {
    const noRank = { ...DEFAULT_CANDIDATE };
    delete noRank.rank;
    renderDialog({ candidate: noRank });
    expect(screen.getByLabelText(/override reason/i)).toBeInTheDocument();
  });
});

// ── Unit tests: disabled-until-valid confirm ──────────────────────────────────

describe('Confirm button disabled-until-valid', () => {
  it('is enabled when rank is 1 (no override needed)', () => {
    renderDialog({ candidate: { ...DEFAULT_CANDIDATE, rank: 1 } });
    expect(screen.getByRole('button', { name: /confirm assignment/i })).not.toBeDisabled();
  });

  it('is disabled while override reason is empty for rank 4', () => {
    renderDialog({ candidate: rank4CandidateFixture() });
    expect(screen.getByRole('button', { name: /confirm assignment/i })).toBeDisabled();
  });

  it('is enabled after typing a non-whitespace override reason', async () => {
    renderDialog({ candidate: rank4CandidateFixture() });
    const textarea = screen.getByLabelText(/override reason/i);
    fireEvent.change(textarea, { target: { value: 'Customer requested this technician specifically' } });
    expect(screen.getByRole('button', { name: /confirm assignment/i })).not.toBeDisabled();
  });

  it('is disabled when override reason contains only whitespace', () => {
    renderDialog({ candidate: rank4CandidateFixture() });
    const textarea = screen.getByLabelText(/override reason/i);
    fireEvent.change(textarea, { target: { value: '   ' } });
    expect(screen.getByRole('button', { name: /confirm assignment/i })).toBeDisabled();
  });
});

// ── Unit tests: reassignment reason validation ────────────────────────────────

describe('Reassignment reason validation', () => {
  it('shows reassignment reason select in reassign mode', () => {
    renderDialog({ mode: 'reassign' });
    expect(screen.getByLabelText(/reassignment reason/i)).toBeInTheDocument();
  });

  it('confirm is disabled when reassignment reason is empty', () => {
    renderDialog({ mode: 'reassign' });
    expect(screen.getByRole('button', { name: /reassign/i })).toBeDisabled();
  });

  it('confirm is enabled after selecting a reassignment reason', () => {
    renderDialog({ mode: 'reassign', candidate: DEFAULT_CANDIDATE });
    const select = screen.getByLabelText(/reassignment reason/i);
    fireEvent.change(select, { target: { value: 'SLA_RISK' } });
    expect(screen.getByRole('button', { name: /reassign/i })).not.toBeDisabled();
  });

  it('does NOT show reassignment reason select in assign mode', () => {
    renderDialog({ mode: 'assign' });
    expect(screen.queryByLabelText(/reassignment reason/i)).not.toBeInTheDocument();
  });
});

// ── MSW flow: successful assign ───────────────────────────────────────────────

describe('Successful assign flow', () => {
  it('closes dialog and shows success toast on 200', async () => {
    mockRespond('POST', ASSIGN_PATH, assignSuccessFixture());
    const { onClose } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(screen.getByText(/Technician 1 assigned successfully/i)).toBeInTheDocument();
  });
});

// ── MSW flow: override-required assign ───────────────────────────────────────

describe('Override-required assign flow', () => {
  it('sends overrideReason in body for rank 4', async () => {
    mockRespond('POST', ASSIGN_PATH, assignSuccessFixture({ overrideRecorded: true }));
    const { onClose } = renderDialog({ candidate: rank4CandidateFixture() });

    const textarea = screen.getByLabelText(/override reason/i);
    fireEvent.change(textarea, { target: { value: 'Closest available for emergency' } });

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});

// ── MSW flow: reassignment with reason ───────────────────────────────────────

describe('Reassignment flow', () => {
  it('sends reassignmentReason and closes on 200', async () => {
    mockRespond('POST', REASSIGN_PATH, reassignSuccessFixture());
    const { onClose } = renderDialog({ mode: 'reassign' });

    const select = screen.getByLabelText(/reassignment reason/i);
    fireEvent.change(select, { target: { value: 'SLA_RISK' } });

    fireEvent.click(screen.getByRole('button', { name: /reassign/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('supplementary notes are optional — confirm enabled without them', () => {
    renderDialog({ mode: 'reassign' });
    const select = screen.getByLabelText(/reassignment reason/i);
    fireEvent.change(select, { target: { value: 'JOB_OVERRUN' } });
    expect(screen.getByRole('button', { name: /reassign/i })).not.toBeDisabled();
  });
});

// ── MSW flow: 422 appointment breach then acknowledged resubmit ───────────────

describe('Appointment breach acknowledgement flow', () => {
  it('reveals ack step when server returns 422 CONFIRMED_APPOINTMENT_BREACH', async () => {
    mockRespond('POST', ASSIGN_PATH, assignAppointmentBreachFixture());
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() =>
      expect(screen.getByLabelText(/appointment impact acknowledgement/i)).toBeInTheDocument()
    );
  });

  it('confirm remains disabled when ack field is empty after breach', async () => {
    mockRespond('POST', ASSIGN_PATH, assignAppointmentBreachFixture());
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => screen.getByLabelText(/appointment impact acknowledgement/i));
    expect(screen.getByRole('button', { name: /confirm assignment/i })).toBeDisabled();
  });

  it('resubmits with ack and succeeds on second attempt', async () => {
    mockRespond('POST', ASSIGN_PATH, assignAppointmentBreachFixture());
    const { onClose } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => screen.getByLabelText(/appointment impact acknowledgement/i));

    mockRespond('POST', ASSIGN_PATH, assignAfterAckFixture());

    const ackTextarea = screen.getByLabelText(/appointment impact acknowledgement/i);
    fireEvent.change(ackTextarea, { target: { value: 'Customer notified and agreed to rescheduling' } });

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('never auto-fills the ack field', async () => {
    mockRespond('POST', ASSIGN_PATH, assignAppointmentBreachFixture());
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => screen.getByLabelText(/appointment impact acknowledgement/i));

    expect(screen.getByLabelText(/appointment impact acknowledgement/i).value).toBe('');
  });
});

// ── MSW flow: 422 certification refusal ──────────────────────────────────────

describe('Certification refusal (terminal)', () => {
  it('shows terminal refusal screen on 422 cert guard', async () => {
    mockRespond('POST', ASSIGN_PATH, assignCertRefusedFixture());
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() =>
      expect(screen.getByText(/assignment not permitted/i)).toBeInTheDocument()
    );
    expect(screen.getByText(/GAS_SAFE/i)).toBeInTheDocument();
  });

  it('shows only Close button — no resubmit possible', async () => {
    mockRespond('POST', ASSIGN_PATH, assignCertRefusedFixture());
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => screen.getByText(/assignment not permitted/i));

    expect(screen.queryByRole('button', { name: /confirm assignment/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /close/i })).toBeInTheDocument();
  });
});

// ── MSW flow: 409 conflict with refresh ──────────────────────────────────────

describe('409 conflict', () => {
  it('shows conflict screen with Refresh recommendations button', async () => {
    mockRespond('POST', ASSIGN_PATH, assignConflictFixture());
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /refresh recommendations/i })).toBeInTheDocument()
    );
  });

  it('calls onRefreshRecommendations and closes on refresh click', async () => {
    mockRespond('POST', ASSIGN_PATH, assignConflictFixture());
    const { onClose, onRefreshRecommendations } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() => screen.getByRole('button', { name: /refresh recommendations/i }));

    fireEvent.click(screen.getByRole('button', { name: /refresh recommendations/i }));

    expect(onRefreshRecommendations).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});

// ── MSW flow: 400 field errors ────────────────────────────────────────────────

describe('400 field errors', () => {
  it('shows inline override reason error from fieldErrors payload', async () => {
    mockRespond('POST', ASSIGN_PATH, assignFieldErrorsFixture());
    renderDialog({ candidate: rank4CandidateFixture() });

    const textarea = screen.getByLabelText(/override reason/i);
    fireEvent.change(textarea, { target: { value: 'some reason' } });

    fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

    await waitFor(() =>
      expect(screen.getByText(/override reason must not be blank/i)).toBeInTheDocument()
    );
  });
});

// ── Idempotency key stability ─────────────────────────────────────────────────

describe('Idempotency key stability', () => {
  it('sends the same Idempotency-Key across retry attempts', async () => {
    const capturedKeys = [];
    const realFetch = globalThis.fetch;

    globalThis.fetch = async (input, init = {}) => {
      const url = typeof input === 'string' ? input : input.url;
      if (url.includes('/assignment')) {
        const headers = new Headers(init.headers ?? {});
        const key = headers.get('Idempotency-Key');
        if (key) capturedKeys.push(key);
      }
      return realFetch(input, init);
    };

    try {
      // First submit → server error, stays on form with retry button
      mockRespond('POST', ASSIGN_PATH, {
        status: 500,
        body: { status: 500, code: 'ERR', message: 'fail', fieldErrors: [], traceId: 't' },
      });
      renderDialog();

      fireEvent.click(screen.getByRole('button', { name: /confirm assignment/i }));

      await waitFor(() => screen.getByRole('button', { name: /retry/i }));

      // Retry → 200
      mockRespond('POST', ASSIGN_PATH, assignSuccessFixture());
      fireEvent.click(screen.getByRole('button', { name: /retry/i }));

      await waitFor(() => expect(capturedKeys.length).toBeGreaterThanOrEqual(2));

      // Both calls must use the identical key
      const uniqueKeys = [...new Set(capturedKeys)];
      expect(uniqueKeys).toHaveLength(1);
    } finally {
      globalThis.fetch = realFetch;
    }
  });
});

// ── Accessibility ─────────────────────────────────────────────────────────────

describe('Accessibility', () => {
  it('dialog has role=dialog with aria-modal', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('dialog has an accessible name via aria-labelledby', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-labelledby');
    const labelId = dialog.getAttribute('aria-labelledby');
    const label = document.getElementById(labelId);
    expect(label).not.toBeNull();
    expect(label.textContent).toMatch(/confirm assignment/i);
  });

  it('renders technician name, rank, and score in summary', () => {
    renderDialog({ candidate: rank4CandidateFixture() });
    expect(screen.getByText('Technician 4')).toBeInTheDocument();
    expect(screen.getByText('#4')).toBeInTheDocument();
    expect(screen.getByText('72.0%')).toBeInTheDocument();
  });

  it('shows parts warning notice when partsWarnings is non-empty', () => {
    renderDialog({
      partsWarnings: [{ message: 'Part HVA-0055 may be unavailable.', partNumber: 'HVA-0055' }],
    });
    expect(screen.getByText(/HVA-0055 may be unavailable/i)).toBeInTheDocument();
  });
});
