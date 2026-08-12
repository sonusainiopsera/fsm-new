/**
 * Tests for CertificationChip — verifies it renders only API-derived values.
 */

import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

import { CertificationChip } from '../CertificationChip.jsx';

describe('CertificationChip', () => {
  it('renders "Current" for current cert with no expiry', () => {
    render(<CertificationChip current={true} daysUntilExpiry={null} expiresOn={null} />);
    expect(screen.getByText(/current/i)).toBeInTheDocument();
  });

  it('renders "Current" with days remaining when daysUntilExpiry > 30', () => {
    render(<CertificationChip current={true} daysUntilExpiry={90} expiresOn="2026-11-10" />);
    const el = screen.getByRole('generic', { hidden: false });
    expect(el.textContent).toMatch(/current/i);
    expect(el.textContent).toMatch(/90d/);
  });

  it('renders "Expiring soon" chip when daysUntilExpiry <= 30', () => {
    render(<CertificationChip current={true} daysUntilExpiry={20} expiresOn="2026-09-01" />);
    expect(screen.getByText(/expiring/i)).toBeInTheDocument();
    expect(screen.getByText(/20d/)).toBeInTheDocument();
  });

  it('renders "Expired" chip when current is false', () => {
    render(<CertificationChip current={false} daysUntilExpiry={null} expiresOn="2024-01-01" />);
    expect(screen.getByText(/expired/i)).toBeInTheDocument();
  });

  it('changes state when API response changes (no client-side date math)', () => {
    // Render once as current
    const { rerender } = render(
      <CertificationChip current={true} daysUntilExpiry={100} expiresOn="2026-12-31" />
    );
    expect(screen.getByText(/current/i)).toBeInTheDocument();

    // Simulate API returning expired on next query (without any date math)
    rerender(
      <CertificationChip current={false} daysUntilExpiry={null} expiresOn="2026-08-11" />
    );
    expect(screen.getByText(/expired/i)).toBeInTheDocument();
  });
});
