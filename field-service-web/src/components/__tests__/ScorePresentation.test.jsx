import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

import { ScorePresentation } from '../ScorePresentation/ScorePresentation.jsx';

const FACTORS = [
  { label: 'Certification match', weight: 0.35, normalizedValue: 0.9 },
  { label: 'Proximity', weight: 0.30, normalizedValue: 0.7 },
];

describe('ScorePresentation', () => {
  it('renders the composite score numeral', () => {
    render(<ScorePresentation score={87} />);
    expect(screen.getByText('87')).toBeInTheDocument();
  });

  it('renders factor labels as visible text', () => {
    render(<ScorePresentation score={87} factors={FACTORS} />);
    expect(screen.getByText('Certification match')).toBeInTheDocument();
    expect(screen.getByText('Proximity')).toBeInTheDocument();
  });

  it('progress bar has correct aria attributes', () => {
    render(<ScorePresentation score={75} maxScore={100} />);
    const bars = screen.getAllByRole('progressbar');
    const mainBar = bars[0];
    expect(mainBar).toHaveAttribute('aria-valuenow', '75');
    expect(mainBar).toHaveAttribute('aria-valuemin', '0');
    expect(mainBar).toHaveAttribute('aria-valuemax', '100');
  });

  it('contains no semantic colour or celebratory CSS class', () => {
    const { container } = render(<ScorePresentation score={100} factors={FACTORS} />);
    const allClasses = Array.from(container.querySelectorAll('*'))
      .flatMap((el) => Array.from(el.classList));

    const celebratoryPatterns = [/success/, /gold/, /medal/, /rosette/, /trophy/, /star/i];
    for (const pattern of celebratoryPatterns) {
      const found = allClasses.find((c) => pattern.test(c));
      expect(found, `Found celebratory class "${found}"`).toBeUndefined();
    }
  });

  it('contains no hardcoded colour tokens like danger, warning on score fills', () => {
    const { container } = render(<ScorePresentation score={100} factors={FACTORS} />);
    const fills = container.querySelectorAll('[class*="fill"]');
    fills.forEach((fill) => {
      expect(fill.className).not.toMatch(/danger/);
      expect(fill.className).not.toMatch(/warning/);
      expect(fill.className).not.toMatch(/success/);
    });
  });
});
