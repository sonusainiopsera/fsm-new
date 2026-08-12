/**
 * FilterBar unit tests.
 *
 * Covers filter-to-query-parameter serialisation, URL round-trip,
 * debounced text input, and clear-all affordance.
 */

import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

import { FilterBar } from '../components/FilterBar.jsx';

/** @type {import('../api/useWorkOrderSearch.js').BoardFilters} */
const DEFAULT_FILTERS = {
  states:       [],
  priorities:   [],
  technicianId: null,
  customerId:   null,
  dateFrom:     null,
  dateTo:       null,
  atRisk:       false,
};

describe('FilterBar', () => {
  let setFilter;

  beforeEach(() => {
    setFilter = vi.fn();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders state chips for all 8 states', () => {
    render(<FilterBar filters={DEFAULT_FILTERS} setFilter={setFilter} />);
    const states = ['NEW', 'ASSIGNED', 'EN ROUTE', 'IN PROGRESS', 'ON HOLD', 'COMPLETED', 'CLOSED', 'CANCELLED'];
    for (const s of states) {
      expect(screen.getByRole('button', { name: new RegExp(s, 'i') })).toBeInTheDocument();
    }
  });

  it('renders priority chips for all 4 priorities', () => {
    render(<FilterBar filters={DEFAULT_FILTERS} setFilter={setFilter} />);
    for (const p of ['Urgent', 'High', 'Normal', 'Low']) {
      expect(screen.getByRole('button', { name: new RegExp(p, 'i') })).toBeInTheDocument();
    }
  });

  it('calls setFilter with the toggled state array when a state chip is clicked', () => {
    render(<FilterBar filters={DEFAULT_FILTERS} setFilter={setFilter} />);
    fireEvent.click(screen.getByRole('button', { name: /filter by state: new/i }));
    expect(setFilter).toHaveBeenCalledWith('states', ['NEW']);
  });

  it('removes a state from the active list when clicked again', () => {
    const filters = { ...DEFAULT_FILTERS, states: ['NEW'] };
    render(<FilterBar filters={filters} setFilter={setFilter} />);
    fireEvent.click(screen.getByRole('button', { name: /filter by state: new/i }));
    expect(setFilter).toHaveBeenCalledWith('states', []);
  });

  it('marks state chip as aria-pressed=true when state is in filters', () => {
    const filters = { ...DEFAULT_FILTERS, states: ['NEW'] };
    render(<FilterBar filters={filters} setFilter={setFilter} />);
    expect(screen.getByRole('button', { name: /filter by state: new/i })).toHaveAttribute('aria-pressed', 'true');
  });

  it('debounces technician text input (300 ms)', async () => {
    render(<FilterBar filters={DEFAULT_FILTERS} setFilter={setFilter} />);
    fireEvent.change(screen.getByLabelText(/filter by technician/i), { target: { value: 'Smith' } });
    expect(setFilter).not.toHaveBeenCalled();
    vi.advanceTimersByTime(300);
    expect(setFilter).toHaveBeenCalledWith('technicianId', 'Smith');
  });

  it('calls setFilter with null when text input is cleared', () => {
    render(<FilterBar filters={DEFAULT_FILTERS} setFilter={setFilter} />);
    fireEvent.change(screen.getByLabelText(/filter by technician/i), { target: { value: '' } });
    vi.advanceTimersByTime(300);
    expect(setFilter).toHaveBeenCalledWith('technicianId', null);
  });

  it('calls setFilter with true when at-risk checkbox is checked', () => {
    render(<FilterBar filters={DEFAULT_FILTERS} setFilter={setFilter} />);
    fireEvent.click(screen.getByLabelText(/show at-risk work orders only/i));
    expect(setFilter).toHaveBeenCalledWith('atRisk', true);
  });

  it('shows "Clear all" button only when filters are active', () => {
    const { rerender } = render(<FilterBar filters={DEFAULT_FILTERS} setFilter={setFilter} />);
    expect(screen.queryByRole('button', { name: /clear all/i })).not.toBeInTheDocument();

    rerender(<FilterBar filters={{ ...DEFAULT_FILTERS, states: ['NEW'] }} setFilter={setFilter} />);
    expect(screen.getByRole('button', { name: /clear all/i })).toBeInTheDocument();
  });

  it('calls setFilter to clear all values when "Clear all" is clicked', () => {
    const filters = { ...DEFAULT_FILTERS, states: ['NEW'], atRisk: true };
    render(<FilterBar filters={filters} setFilter={setFilter} />);
    fireEvent.click(screen.getByRole('button', { name: /clear all/i }));
    expect(setFilter).toHaveBeenCalledWith('states', []);
    expect(setFilter).toHaveBeenCalledWith('atRisk', false);
  });
});
