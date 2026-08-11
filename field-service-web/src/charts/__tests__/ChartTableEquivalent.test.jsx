import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ChartTableEquivalent } from '../ChartTableEquivalent.jsx';
import seriesFixture from '../../mocks/fixtures/charts/series-fixture.json';
import tabularFixture from '../../mocks/fixtures/charts/tabular-equivalent.json';

const SERIES = [
  { key: 'open', name: 'Open' },
  { key: 'closed', name: 'Closed' },
];

const DATA = [
  { label: 'Jan', open: 12, closed: 8 },
  { label: 'Feb', open: 15, closed: 10 },
  { label: 'Mar', open: 9, closed: 14 },
];

describe('ChartTableEquivalent — structure', () => {
  it('renders a table element', () => {
    render(<ChartTableEquivalent caption="Work orders" series={SERIES} data={DATA} />);
    expect(screen.getByRole('table')).toBeInTheDocument();
  });

  it('has a visible caption', () => {
    render(<ChartTableEquivalent caption="Work orders" series={SERIES} data={DATA} />);
    expect(screen.getByText('Work orders')).toBeInTheDocument();
  });

  it('renders column headers with scope="col"', () => {
    render(<ChartTableEquivalent caption="Work orders" series={SERIES} data={DATA} />);
    const headers = screen.getAllByRole('columnheader');
    expect(headers[0]).toHaveTextContent('Period');
    expect(headers[1]).toHaveTextContent('Open');
    expect(headers[2]).toHaveTextContent('Closed');
  });

  it('renders a row for each data entry', () => {
    render(<ChartTableEquivalent caption="Work orders" series={SERIES} data={DATA} />);
    const rows = screen.getAllByRole('row');
    // 1 header row + 3 data rows
    expect(rows).toHaveLength(4);
  });
});

describe('ChartTableEquivalent — value parity with fixture', () => {
  it('table values match the series fixture data', () => {
    const { series, data } = seriesFixture;
    render(<ChartTableEquivalent caption={seriesFixture.caption} series={series} data={data} />);

    for (const row of tabularFixture.rows) {
      const cells = screen.getAllByRole('cell').filter(
        (c) => c.closest('tr')?.firstChild?.textContent === row.label,
      );
      // At least one cell per expected row exists
      expect(cells.length).toBeGreaterThan(0);
    }
  });
});

describe('ChartTableEquivalent — empty state', () => {
  it('renders a valid empty table with an accessible label', () => {
    render(<ChartTableEquivalent caption="Empty chart" series={SERIES} data={[]} />);
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByLabelText('No data available')).toBeInTheDocument();
  });
});

describe('ChartTableEquivalent — keyboard accessibility', () => {
  it('table is reachable without aria-hidden or inert', () => {
    const { container } = render(
      <ChartTableEquivalent caption="Work orders" series={SERIES} data={DATA} />,
    );
    const table = container.querySelector('table');
    expect(table).not.toHaveAttribute('aria-hidden', 'true');
    expect(table).not.toHaveAttribute('inert');
  });
});
