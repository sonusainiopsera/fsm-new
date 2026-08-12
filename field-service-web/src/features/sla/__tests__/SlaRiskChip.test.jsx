import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SlaRiskChip, formatSlaMinutes } from '../SlaRiskChip.jsx'

// ── formatSlaMinutes ──────────────────────────────────────────────────────────

describe('formatSlaMinutes', () => {
  it('returns overrun label for 0 minutes', () => {
    expect(formatSlaMinutes(0)).toMatch(/overrun/i)
  })

  it('returns overrun label for negative minutes', () => {
    expect(formatSlaMinutes(-15)).toMatch(/overrun/i)
  })

  it('formats sub-hour as Xm', () => {
    expect(formatSlaMinutes(25)).toBe('25m')
    expect(formatSlaMinutes(59)).toBe('59m')
    expect(formatSlaMinutes(1)).toBe('1m')
  })

  it('formats exactly 60 minutes as 1h 0m', () => {
    expect(formatSlaMinutes(60)).toMatch(/1h/)
  })

  it('formats hours and minutes', () => {
    expect(formatSlaMinutes(90)).toMatch(/1h.*30m/)
    expect(formatSlaMinutes(125)).toMatch(/2h.*5m/)
  })
})

// ── SlaRiskChip — rendering ───────────────────────────────────────────────────

describe('SlaRiskChip', () => {
  it('renders a breached chip with icon and label text', () => {
    render(
      <SlaRiskChip riskLevel="breached" minutesRemaining={-15} />
    )
    // Should render some label text (not colour-only)
    expect(screen.getByRole('status')).toBeTruthy()
    const status = screen.getByRole('status')
    expect(status.textContent).toMatch(/breach/i)
  })

  it('renders an at_risk chip', () => {
    render(
      <SlaRiskChip riskLevel="at_risk" minutesRemaining={25} />
    )
    const status = screen.getByRole('status')
    expect(status.textContent).toMatch(/risk/i)
  })

  it('renders a healthy chip', () => {
    render(
      <SlaRiskChip riskLevel="healthy" minutesRemaining={120} />
    )
    const status = screen.getByRole('status')
    expect(status.textContent).toMatch(/on.?track|healthy/i)
  })

  it('shows minutes remaining when provided', () => {
    render(
      <SlaRiskChip riskLevel="at_risk" minutesRemaining={42} />
    )
    expect(screen.getByRole('status').textContent).toContain('42m')
  })

  it('applies stale affordance — dashed outline and stale icon', () => {
    const { container } = render(
      <SlaRiskChip riskLevel="at_risk" minutesRemaining={25} stale />
    )
    const chip = container.firstChild
    // Should have some visual stale indicator
    expect(chip.textContent).toMatch(/⏷|stale/i)
  })

  it('shows attribution button for breached rows when onAttribute provided', () => {
    const onAttribute = vi.fn()
    render(
      <SlaRiskChip
        riskLevel="breached"
        minutesRemaining={-5}
        onAttribute={onAttribute}
      />
    )
    const btn = screen.getByRole('button')
    expect(btn).toBeTruthy()
    // Min touch target ≥ 44px enforced by the component styles
  })

  it('does not show attribution button for at_risk rows', () => {
    render(
      <SlaRiskChip riskLevel="at_risk" minutesRemaining={10} onAttribute={vi.fn()} />
    )
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('does not show attribution button for healthy rows', () => {
    render(
      <SlaRiskChip riskLevel="healthy" minutesRemaining={60} onAttribute={vi.fn()} />
    )
    expect(screen.queryByRole('button')).toBeNull()
  })
})
