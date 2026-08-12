/**
 * Unit tests for WO-157: log-work screen components.
 *
 * Covers:
 * - validateLabourForm: duration and range modes, edge cases
 * - partsRowReducer: add, set, remove, clear-errors
 * - buildShortfallMessage: formats shortfall copy for hold route
 * - idempotency key generation stability
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { validateLabourForm } from '../components/TimeEntryCard.jsx';
import { partsRowReducer, createEmptyRow, buildShortfallMessage } from '../components/PartsRowsList.jsx';
import { newAttemptKey } from '../../../lib/idempotency.js';

// ──────────────────────────────────────────────────────────────────────────────
// validateLabourForm
// ──────────────────────────────────────────────────────────────────────────────
describe('validateLabourForm — duration mode', () => {
  it('passes when durationMinutes is in range', () => {
    const errs = validateLabourForm('duration', { durationMinutes: '90', note: '' });
    expect(Object.keys(errs)).toHaveLength(0);
  });

  it('rejects missing duration', () => {
    const errs = validateLabourForm('duration', { durationMinutes: '', note: '' });
    expect(errs.durationMinutes).toBeTruthy();
  });

  it('rejects 0 minutes', () => {
    const errs = validateLabourForm('duration', { durationMinutes: '0', note: '' });
    expect(errs.durationMinutes).toBeTruthy();
  });

  it('rejects > 1440 minutes (24 h)', () => {
    const errs = validateLabourForm('duration', { durationMinutes: '1441', note: '' });
    expect(errs.durationMinutes).toBeTruthy();
  });

  it('accepts exactly 1440 minutes', () => {
    const errs = validateLabourForm('duration', { durationMinutes: '1440', note: '' });
    expect(Object.keys(errs)).toHaveLength(0);
  });
});

describe('validateLabourForm — range mode', () => {
  const valid = { startedAt: '2026-08-12T09:00', endedAt: '2026-08-12T10:30', note: '' };

  it('passes with valid start/end', () => {
    expect(Object.keys(validateLabourForm('range', valid))).toHaveLength(0);
  });

  it('rejects missing startedAt', () => {
    const errs = validateLabourForm('range', { ...valid, startedAt: '' });
    expect(errs.startedAt).toBeTruthy();
  });

  it('rejects missing endedAt', () => {
    const errs = validateLabourForm('range', { ...valid, endedAt: '' });
    expect(errs.endedAt).toBeTruthy();
  });

  it('rejects end before start', () => {
    const errs = validateLabourForm('range', {
      startedAt: '2026-08-12T10:00',
      endedAt:   '2026-08-12T09:00',
      note: '',
    });
    expect(errs.endedAt).toBeTruthy();
  });

  it('rejects end equal to start', () => {
    const errs = validateLabourForm('range', {
      startedAt: '2026-08-12T09:00',
      endedAt:   '2026-08-12T09:00',
      note: '',
    });
    expect(errs.endedAt).toBeTruthy();
  });
});

describe('validateLabourForm — note', () => {
  it('rejects note > 500 characters', () => {
    const errs = validateLabourForm('duration', {
      durationMinutes: '30',
      note: 'x'.repeat(501),
    });
    expect(errs.note).toBeTruthy();
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// partsRowReducer
// ──────────────────────────────────────────────────────────────────────────────
describe('partsRowReducer', () => {
  it('ADD_ROW adds a new empty row', () => {
    const rows = partsRowReducer([], { type: 'ADD_ROW' });
    expect(rows).toHaveLength(1);
    expect(rows[0].partId).toBeNull();
    expect(rows[0].quantity).toBe('1');
  });

  it('SET_PART updates the partId for the matching row', () => {
    const initial = [createEmptyRow()];
    const rows = partsRowReducer(initial, {
      type: 'SET_PART',
      payload: { rowId: initial[0].rowId, partId: 'part-abc' },
    });
    expect(rows[0].partId).toBe('part-abc');
    expect(rows[0].error).toBeNull();
  });

  it('SET_QUANTITY updates the quantity for the matching row', () => {
    const initial = [createEmptyRow()];
    const rows = partsRowReducer(initial, {
      type: 'SET_QUANTITY',
      payload: { rowId: initial[0].rowId, quantity: '3' },
    });
    expect(rows[0].quantity).toBe('3');
  });

  it('SET_ERROR sets the error message on a row', () => {
    const initial = [createEmptyRow()];
    const rows = partsRowReducer(initial, {
      type: 'SET_ERROR',
      payload: { rowId: initial[0].rowId, error: 'requested 3, available 1' },
    });
    expect(rows[0].error).toBe('requested 3, available 1');
  });

  it('REMOVE_ROW removes only the targeted row', () => {
    const r1 = createEmptyRow();
    const r2 = createEmptyRow();
    const rows = partsRowReducer([r1, r2], {
      type: 'REMOVE_ROW',
      payload: { rowId: r1.rowId },
    });
    expect(rows).toHaveLength(1);
    expect(rows[0].rowId).toBe(r2.rowId);
  });

  it('CLEAR_ERRORS clears all row errors', () => {
    const r = { ...createEmptyRow(), error: 'some error' };
    const rows = partsRowReducer([r], { type: 'CLEAR_ERRORS' });
    expect(rows[0].error).toBeNull();
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// buildShortfallMessage
// ──────────────────────────────────────────────────────────────────────────────
describe('buildShortfallMessage', () => {
  it('includes partCode and the server detail in output', () => {
    const msg = buildShortfallMessage('COMP-4470', 'requested 3, available 1');
    expect(msg).toContain('COMP-4470');
    expect(msg).toContain('requested 3, available 1');
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// idempotency key stability
// ──────────────────────────────────────────────────────────────────────────────
describe('newAttemptKey', () => {
  it('generates a valid UUID each call', () => {
    const key = newAttemptKey();
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  });

  it('generates a different key on each invocation (new intent)', () => {
    const k1 = newAttemptKey();
    const k2 = newAttemptKey();
    expect(k1).not.toBe(k2);
  });
});
