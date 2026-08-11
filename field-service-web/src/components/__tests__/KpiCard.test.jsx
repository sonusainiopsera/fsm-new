import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

import { DensityProvider } from '../../density/DensityContext.js';
import { KpiCard } from '../KpiCard/KpiCard.jsx';

function renderCard(props) {
  return render(<DensityProvider><KpiCard {...props} /></DensityProvider>);
}

describe('KpiCard', () => {
  it('renders label and value', () => {
    renderCard({ label: 'Open Orders', value: 42 });
    expect(screen.getByText('Open Orders')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('renders delta with sign when positive', () => {
    renderCard({ label: 'Open', value: 10, delta: 3 });
    expect(screen.getByText(/\+3/)).toBeInTheDocument();
  });

  it('renders negative delta', () => {
    renderCard({ label: 'Open', value: 10, delta: -2 });
    expect(screen.getByText(/-2/)).toBeInTheDocument();
  });

  it('omits delta chip when delta is null', () => {
    const { container } = renderCard({ label: 'Open', value: 42 });
    expect(container.querySelector('[class*="delta"]')).not.toBeInTheDocument();
  });

  it('renders target progressbar when target and current provided', () => {
    renderCard({ label: 'SLA', value: '94%', target: 95, current: 94 });
    const bar = screen.getByRole('progressbar');
    expect(bar).toBeInTheDocument();
    expect(bar).toHaveAttribute('aria-valuenow', '99');
  });

  it('omits progressbar when target absent', () => {
    renderCard({ label: 'Open', value: 10 });
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  it('has hover-only border change — no elevation class at rest', () => {
    const { container } = renderCard({ label: 'Test', value: 1 });
    const card = container.querySelector('article');
    expect(card.className).not.toMatch(/elevation/);
    expect(card.className).not.toMatch(/shadow/);
  });

  it('renders sparkline SVG when data provided', () => {
    const { container } = renderCard({ label: 'Trend', value: 5, sparklineData: [1, 2, 3, 4, 5] });
    expect(container.querySelector('svg')).toBeInTheDocument();
  });
});
