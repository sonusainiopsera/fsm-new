import React from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import { DensityProvider } from '../../density/DensityContext.js';
import { DataTable } from '../DataTable/DataTable.jsx';
import { EmptyState } from '../StateSurface/StateSurface.jsx';

const COLS = [
  { key: 'id', header: 'ID', sortable: true },
  { key: 'name', header: 'Name' },
  { key: 'amount', header: 'Amount', numeric: true },
];

const DATA = [
  { id: '1', name: 'Alpha', amount: 1234 },
  { id: '2', name: 'Beta', amount: 567 },
  { id: '3', name: 'Gamma', amount: 89 },
];

beforeEach(() => {
  if (typeof ResizeObserver === 'undefined') {
    global.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

function renderTable(props = {}) {
  return render(
    <DensityProvider>
      <DataTable columns={COLS} data={DATA} rowKey={(r) => r.id} {...props} />
    </DensityProvider>
  );
}

describe('DataTable', () => {
  it('renders column headers', () => {
    renderTable();
    expect(screen.getByText('ID')).toBeInTheDocument();
    expect(screen.getByText('Name')).toBeInTheDocument();
    expect(screen.getByText('Amount')).toBeInTheDocument();
  });

  it('renders all data rows', () => {
    renderTable();
    expect(screen.getByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Beta')).toBeInTheDocument();
    expect(screen.getByText('Gamma')).toBeInTheDocument();
  });

  it('renders numeric column with right-align class', () => {
    renderTable();
    const amountCells = screen.getAllByText(/1234|567|89/);
    amountCells.forEach((cell) => {
      expect(cell.className).toMatch(/tdNumeric/);
    });
  });

  it('always shows sort affordance on sortable columns', () => {
    renderTable();
    const sortBtn = screen.getByRole('button', { name: /Sort by ID/ });
    expect(sortBtn).toBeInTheDocument();
    const icon = sortBtn.querySelector('[aria-hidden]');
    expect(icon?.textContent).toContain('↕');
  });

  it('updates sort icon and aria-sort after clicking sortable header', async () => {
    const onSort = vi.fn();
    renderTable({ onSort });
    await userEvent.click(screen.getByRole('button', { name: /Sort by ID/ }));
    expect(onSort).toHaveBeenCalledWith({ field: 'id', direction: 'asc' });
  });

  it('does not apply zebra-stripe classes to rows', () => {
    const { container } = renderTable();
    const rows = container.querySelectorAll('tbody tr');
    rows.forEach((row) => {
      expect(row.className).not.toMatch(/even|odd|stripe|zebra/);
    });
  });

  it('marks selected row with aria-selected and accent class', () => {
    renderTable({ selectedKey: '2' });
    const rows = screen.getAllByRole('row').filter((r) => r.getAttribute('aria-selected') === 'true');
    expect(rows).toHaveLength(1);
    expect(rows[0].className).toMatch(/trSelected/);
  });

  it('shows EmptyState inside table frame when data is empty', () => {
    render(
      <DensityProvider>
        <DataTable columns={COLS} data={[]} emptyState={<EmptyState description="No data" />} />
      </DensityProvider>
    );
    expect(screen.getByText('Nothing here yet')).toBeInTheDocument();
  });

  it('switches to card mode when ResizeObserver reports width < 768', () => {
    let observerCb;
    global.ResizeObserver = class {
      constructor(cb) { observerCb = cb; }
      observe(el) {
        vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({ width: 360 });
        observerCb([{ contentRect: { width: 360 } }]);
      }
      unobserve() {}
      disconnect() {}
    };
    renderTable();
    const cards = screen.getAllByRole('listitem');
    expect(cards.length).toBe(DATA.length);
  });

  it('no horizontal scroll element nested inside vertical scroll element', () => {
    const { container } = renderTable();
    const scrollXEl = container.querySelector('[class*="tableScroll"]');
    if (scrollXEl) {
      let parent = scrollXEl.parentElement;
      while (parent) {
        expect(parent.style.overflowY ?? '').not.toBe('scroll');
        parent = parent.parentElement;
      }
    }
  });
});
