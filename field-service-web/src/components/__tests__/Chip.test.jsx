import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

import { DensityProvider } from '../../density/DensityContext.js';
import { Chip } from '../Chip/Chip.jsx';

const PRIORITY_VALUES = ['urgent', 'high', 'normal', 'low'];
const STATE_VALUES = ['new', 'assigned', 'en_route', 'in_progress', 'on_hold', 'completed', 'closed', 'cancelled'];
const RISK_VALUES = ['high', 'medium', 'low'];

function renderChip(props) {
  return render(<DensityProvider><Chip {...props} /></DensityProvider>);
}

describe('Chip', () => {
  describe('priority variant — text label + icon present for every enum value', () => {
    PRIORITY_VALUES.forEach((v) => {
      it(`renders text and icon for priority "${v}"`, () => {
        renderChip({ variant: 'priority', value: v });
        const chip = screen.getByRole('status');
        expect(chip).toBeInTheDocument();
        expect(chip.textContent.length).toBeGreaterThan(0);
        const icon = chip.querySelector('[data-shape]');
        expect(icon).toBeInTheDocument();
      });
    });
  });

  describe('state variant — text label + icon for every enum value', () => {
    STATE_VALUES.forEach((v) => {
      it(`renders text and icon for state "${v}"`, () => {
        renderChip({ variant: 'state', value: v });
        const chip = screen.getByRole('status');
        expect(chip.textContent.length).toBeGreaterThan(0);
        const icon = chip.querySelector('[data-shape]');
        expect(icon).toBeInTheDocument();
      });
    });
  });

  describe('risk variant — text label + icon for every enum value', () => {
    RISK_VALUES.forEach((v) => {
      it(`renders text and icon for risk "${v}"`, () => {
        renderChip({ variant: 'risk', value: v });
        const chip = screen.getByRole('status');
        expect(chip.textContent.length).toBeGreaterThan(0);
        const icon = chip.querySelector('[data-shape]');
        expect(icon).toBeInTheDocument();
      });
    });
  });

  it('renders neutral fallback for unrecognised priority value', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderChip({ variant: 'priority', value: 'galaxy-brain' });
    const chip = screen.getByRole('status');
    expect(chip.className).toMatch(/neutral/);
    expect(chip.textContent).toContain('galaxy-brain');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('unrecognised value'));
    warn.mockRestore();
  });

  it('accepts a custom label override', () => {
    renderChip({ variant: 'priority', value: 'urgent', label: 'Custom Label' });
    expect(screen.getByText('Custom Label')).toBeInTheDocument();
  });

  it('renders with compact density class', () => {
    render(<DensityProvider density="compact"><Chip variant="priority" value="high" /></DensityProvider>);
    const chip = screen.getByRole('status');
    expect(chip.className).toMatch(/compact/);
  });
});
