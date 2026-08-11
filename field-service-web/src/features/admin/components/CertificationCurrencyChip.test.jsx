/**
 * @fileoverview Unit tests for CertificationCurrencyChip and deriveCurrencyStatus.
 *
 * Verifies: the chip trusts API-derived currency; no client-side date math (AC-5).
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CertificationCurrencyChip, deriveCurrencyStatus } from './CertificationCurrencyChip.jsx'

describe('deriveCurrencyStatus', () => {
  it('returns "current" when current=true and daysUntilExpiry > 30', () => {
    expect(deriveCurrencyStatus({ current: true, daysUntilExpiry: 60 })).toBe('current')
  })

  it('returns "expiring-soon" when current=true and daysUntilExpiry <= 30', () => {
    expect(deriveCurrencyStatus({ current: true, daysUntilExpiry: 15 })).toBe('expiring-soon')
  })

  it('returns "expiring-soon" at exactly 30 days', () => {
    expect(deriveCurrencyStatus({ current: true, daysUntilExpiry: 30 })).toBe('expiring-soon')
  })

  it('returns "expired" when current=false', () => {
    expect(deriveCurrencyStatus({ current: false, daysUntilExpiry: null })).toBe('expired')
  })

  it('returns "current" when current=true and daysUntilExpiry is null (perpetual)', () => {
    expect(deriveCurrencyStatus({ current: true, daysUntilExpiry: null })).toBe('current')
  })

  it('never computes expiry from a date — relies solely on API fields', () => {
    // If daysUntilExpiry=null and current=false, it must show expired regardless of expiresOn
    expect(deriveCurrencyStatus({ current: false, daysUntilExpiry: null, expiresOn: '2099-12-31' })).toBe('expired')
  })
})

describe('CertificationCurrencyChip', () => {
  it('renders a current chip with visible text (not colour-only)', () => {
    render(<CertificationCurrencyChip current={true} daysUntilExpiry={90} />)
    const chip = screen.getByRole('status')
    expect(chip.textContent).toMatch(/current/i)
  })

  it('renders an expiring-soon chip with visible text', () => {
    render(<CertificationCurrencyChip current={true} daysUntilExpiry={10} />)
    const chip = screen.getByRole('status')
    expect(chip.textContent).toMatch(/expir/i)
  })

  it('renders an expired chip with visible text', () => {
    render(<CertificationCurrencyChip current={false} daysUntilExpiry={null} />)
    const chip = screen.getByRole('status')
    expect(chip.textContent).toMatch(/expired/i)
  })

  it('exposes accessible label on the chip', () => {
    render(<CertificationCurrencyChip current={true} daysUntilExpiry={5} />)
    const chip = screen.getByRole('status')
    expect(chip.getAttribute('aria-label')).toBeTruthy()
  })
})
