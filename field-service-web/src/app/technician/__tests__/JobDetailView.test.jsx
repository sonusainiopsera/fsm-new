/**
 * Unit tests for WO-156: job detail view, action bar, countdown and error handling.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { SlaCountdownChip, formatCountdown } from '../components/SlaCountdownChip.jsx';
import { HoldReasonSheet } from '../components/HoldReasonSheet.jsx';
import { TransitionActionBar } from '../components/TransitionActionBar.jsx';
import { mapApiError } from '../../../shared/api/errorMapping.js';

// ──────────────────────────────────────────────────────────────────────────────
// formatCountdown
// ──────────────────────────────────────────────────────────────────────────────
describe('formatCountdown', () => {
  const now = new Date('2026-08-12T12:00:00Z');

  it('returns "No deadline" when deadline is null', () => {
    const { label } = formatCountdown(null, now);
    expect(label).toBe('No deadline');
  });

  it('shows overrun when deadline has passed', () => {
    const past = '2026-08-12T11:30:00Z';
    const { label, overrun } = formatCountdown(past, now);
    expect(overrun).toBe(true);
    expect(label).toMatch(/overrun/);
  });

  it('shows minutes remaining when < 60 minutes left', () => {
    const soon = '2026-08-12T12:45:00Z';
    const { label, overrun } = formatCountdown(soon, now);
    expect(overrun).toBe(false);
    expect(label).toMatch(/m$/);
  });

  it('shows hours when >= 60 minutes remaining', () => {
    const later = '2026-08-12T14:30:00Z';
    const { label } = formatCountdown(later, now);
    expect(label).toMatch(/h/);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// SlaCountdownChip rendering
// ──────────────────────────────────────────────────────────────────────────────
describe('SlaCountdownChip', () => {
  it('renders with a deadline', () => {
    render(
      <SlaCountdownChip
        resolutionDeadline="2026-08-12T17:00:00Z"
        slaAtRisk={false}
      />
    );
    expect(screen.getByRole('status')).toBeTruthy();
  });

  it('includes at-risk in aria-label when flagged', () => {
    render(
      <SlaCountdownChip
        resolutionDeadline="2026-08-12T17:00:00Z"
        slaAtRisk={true}
      />
    );
    const el = screen.getByRole('status');
    expect(el.getAttribute('aria-label')).toContain('at risk');
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// mapApiError — error mapping branches
// ──────────────────────────────────────────────────────────────────────────────
describe('mapApiError', () => {
  it('maps 409 to CONFLICT_REFRESH', () => {
    const err = { status: 409, code: 'ANY', message: 'conflict' };
    expect(mapApiError(err).type).toBe('CONFLICT_REFRESH');
  });

  it('maps 422 to GUARD_MESSAGE with server text', () => {
    const err = {
      status: 422,
      code: 'GUARD',
      message: 'server message',
      fieldErrors: [{ field: 'guard', message: 'Labour time must be recorded.' }],
    };
    const mapped = mapApiError(err);
    expect(mapped.type).toBe('GUARD_MESSAGE');
    expect(mapped.message).toBe('Labour time must be recorded.');
  });

  it('maps 400 to FIELD_ERRORS', () => {
    const err = { status: 400, code: 'BAD', message: 'bad', fieldErrors: [] };
    expect(mapApiError(err).type).toBe('FIELD_ERRORS');
  });

  it('maps 403 to PERMISSION', () => {
    const err = { status: 403, code: 'FORBIDDEN', message: 'nope' };
    expect(mapApiError(err).type).toBe('PERMISSION');
  });

  it('maps 503 to DEGRADED', () => {
    const err = { status: 503, code: 'SERVICE_UNAVAILABLE', message: 'down' };
    expect(mapApiError(err).type).toBe('DEGRADED');
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// HoldReasonSheet — form validation
// ──────────────────────────────────────────────────────────────────────────────
describe('HoldReasonSheet', () => {
  const holdReasons = [
    { code: 'AWAITING_PARTS',       label: 'Awaiting parts',       sortOrder: 10 },
    { code: 'CUSTOMER_UNAVAILABLE', label: 'Customer unavailable', sortOrder: 20 },
  ];

  it('blocks submit without a selected reason', () => {
    const onConfirm = vi.fn();
    render(
      <HoldReasonSheet
        open={true}
        holdReasons={holdReasons}
        onConfirm={onConfirm}
        onCancel={() => {}}
        isPending={false}
      />
    );
    fireEvent.click(screen.getByText('Place on hold'));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeTruthy();
  });

  it('calls onConfirm with reason code when a reason is selected', () => {
    const onConfirm = vi.fn();
    render(
      <HoldReasonSheet
        open={true}
        holdReasons={holdReasons}
        onConfirm={onConfirm}
        onCancel={() => {}}
        isPending={false}
      />
    );
    fireEvent.click(screen.getByLabelText('Awaiting parts'));
    fireEvent.click(screen.getByText('Place on hold'));
    expect(onConfirm).toHaveBeenCalledWith('AWAITING_PARTS', '');
  });

  it('does not render when open=false', () => {
    const { container } = render(
      <HoldReasonSheet
        open={false}
        holdReasons={holdReasons}
        onConfirm={() => {}}
        onCancel={() => {}}
        isPending={false}
      />
    );
    expect(container.firstChild).toBeNull();
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// TransitionActionBar — action-to-button mapping
// ──────────────────────────────────────────────────────────────────────────────
describe('TransitionActionBar', () => {
  function renderBar(allowedTransitions, holdReasons = []) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={qc}>
        <TransitionActionBar
          workOrderId="wo-test"
          expectedVersion={1}
          allowedTransitions={allowedTransitions}
          holdReasons={holdReasons}
          onSuccess={() => {}}
        />
      </QueryClientProvider>
    );
  }

  it('renders exactly the events in allowedTransitions', () => {
    renderBar(['DEPART']);
    expect(screen.getByText('Depart')).toBeTruthy();
    expect(screen.queryByText('Start job')).toBeNull();
  });

  it('renders read-only state when allowedTransitions is empty', () => {
    renderBar([]);
    expect(screen.getByText(/no actions available/i)).toBeTruthy();
  });

  it('disables HOLD button when holdReasons is empty', () => {
    renderBar(['COMPLETE', 'HOLD'], []);
    const holdBtn = screen.getByText('Place on hold').closest('button');
    expect(holdBtn).toBeTruthy();
    expect(holdBtn.disabled).toBe(true);
  });

  it('removes a button when the event is absent from allowedTransitions', () => {
    renderBar(['START']);
    expect(screen.queryByText('Place on hold')).toBeNull();
    expect(screen.queryByText('Depart')).toBeNull();
    expect(screen.getByText('Start job')).toBeTruthy();
  });
});
