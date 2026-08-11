import { render, screen } from '@testing-library/react'
import { KpiCard } from './KpiCard.jsx'

describe('KpiCard', () => {
  it('renders label and value', () => {
    render(<KpiCard label="SLA Compliance" value="94.2" unit="%" />)
    expect(screen.getByText('SLA Compliance')).toBeInTheDocument()
    expect(screen.getByText('94.2')).toBeInTheDocument()
  })

  it('renders unit when provided', () => {
    render(<KpiCard label="Resolution" value="3.4" unit="hrs" />)
    expect(screen.getByText('hrs')).toBeInTheDocument()
  })

  it('renders positive delta chip', () => {
    render(<KpiCard label="SLA" value="94" delta={2.1} />)
    expect(screen.getByText(/2\.1/)).toBeInTheDocument()
  })

  it('renders negative delta chip', () => {
    render(<KpiCard label="Time" value="3.4" delta={-8.2} />)
    expect(screen.getByText(/-8\.2/)).toBeInTheDocument()
  })

  it('omits delta when null', () => {
    const { container } = render(<KpiCard label="Count" value="47" delta={null} />)
    expect(container.querySelectorAll('[data-delta]')).toHaveLength(0)
  })

  it('omits delta when undefined', () => {
    const { container } = render(<KpiCard label="Count" value="47" />)
    expect(container.querySelectorAll('[data-delta]')).toHaveLength(0)
  })

  it('renders progress bar toward target', () => {
    render(<KpiCard label="SLA" value="94.2" target={100} currentRaw={94.2} />)
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  it('omits progress bar when no target', () => {
    const { container } = render(<KpiCard label="Count" value="47" />)
    expect(container.querySelectorAll('[role="progressbar"]')).toHaveLength(0)
  })
})
