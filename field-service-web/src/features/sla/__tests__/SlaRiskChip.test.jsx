/**
 * SlaRiskChip — unit tests for state presentation and formatting logic.
 */

import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

import { SlaRiskChip } from '../SlaRiskChip.jsx';

describe('SlaRiskChip', () => {
  describe('risk states', () => {
    it('renders healthy state with on-track label', () => {
      render(<SlaRiskChip riskState="healthy" />);
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', expect.stringContaining('On track'));
    });

    it('renders at-risk state with at-risk label', () => {
      render(<SlaRiskChip riskState="at-risk" />);
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', expect.stringContaining('At risk'));
    });

    it('renders breached state with breached label', () => {
      render(<SlaRiskChip riskState="breached" />);
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', expect.stringContaining('Breached'));
    });
  });

  describe('minutes formatting', () => {
    it('shows minutes remaining when positive', () => {
      render(<SlaRiskChip riskState="at-risk" minutesRemaining={30} />);
      const el = screen.getByRole('status');
      expect(el).toHaveAttribute('aria-label', expect.stringContaining('30m remaining'));
    });

    it('shows hours and minutes when >= 60', () => {
      render(<SlaRiskChip riskState="at-risk" minutesRemaining={90} />);
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', expect.stringContaining('1h 30m remaining'));
    });

    it('shows whole hours when no remainder', () => {
      render(<SlaRiskChip riskState="at-risk" minutesRemaining={120} />);
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', expect.stringContaining('2h remaining'));
    });

    it('shows due-now at zero minutes', () => {
      render(<SlaRiskChip riskState="at-risk" minutesRemaining={0} />);
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', expect.stringContaining('Due now'));
    });

    it('shows overrun label for negative remaining', () => {
      render(<SlaRiskChip riskState="breached" minutesRemaining={-15} />);
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', expect.stringContaining('15m overrun'));
    });

    it('omits time label when minutesRemaining is null', () => {
      render(<SlaRiskChip riskState="healthy" minutesRemaining={null} />);
      const label = screen.getByRole('status').getAttribute('aria-label');
      expect(label).not.toMatch(/remaining|overrun/);
    });
  });

  describe('stale affordance', () => {
    it('adds out-of-date qualifier to aria-label when stale', () => {
      render(<SlaRiskChip riskState="at-risk" minutesRemaining={20} stale />);
      expect(screen.getByRole('status')).toHaveAttribute(
        'aria-label',
        expect.stringContaining('possibly out of date'),
      );
    });

    it('does not add out-of-date qualifier when not stale', () => {
      render(<SlaRiskChip riskState="at-risk" minutesRemaining={20} />);
      expect(screen.getByRole('status')).not.toHaveAttribute(
        'aria-label',
        expect.stringContaining('out of date'),
      );
    });
  });

  describe('icon + text encoding (non-colour-only)', () => {
    it('renders visible text label alongside icon', () => {
      render(<SlaRiskChip riskState="breached" />);
      // Both icon (aria-hidden) and text label present
      expect(screen.getByText('Breached')).toBeInTheDocument();
    });
  });
});
