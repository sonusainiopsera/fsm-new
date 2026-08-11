import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { ChartTableEquivalent } from './ChartTableEquivalent.jsx'

const SAMPLE_DATA = [
  { name: 'Jan', revenue: 4000, cost: 2400 },
  { name: 'Feb', revenue: 3000, cost: 1398 },
  { name: 'Mar', revenue: 5200, cost: 3100 },
]

const SAMPLE_SERIES = [
  { key: 'revenue', label: 'Revenue' },
  { key: 'cost', label: 'Cost' },
]

describe('ChartTableEquivalent — structure', () => {
  it('renders a table with accessible role', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />)
    expect(screen.getByRole('table', { name: 'Monthly P&L' })).toBeInTheDocument()
  })

  it('renders a column header per series key', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />)
    expect(screen.getByRole('columnheader', { name: 'Revenue' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Cost' })).toBeInTheDocument()
  })

  it('renders a row per data point', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />)
    const rows = screen.getAllByRole('row')
    // 1 header row + 3 data rows
    expect(rows).toHaveLength(4)
  })

  it('renders a caption matching the caption prop', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Revenue Breakdown" />)
    expect(screen.getByText('Revenue Breakdown')).toBeInTheDocument()
  })
})

describe('ChartTableEquivalent — value parity', () => {
  it('table cells contain the same values as the data series props', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />)

    for (const row of SAMPLE_DATA) {
      for (const s of SAMPLE_SERIES) {
        // Find the cell with data-series attribute matching the series key in the row
        const cells = screen.getAllByRole('cell').filter(
          cell => cell.getAttribute('data-series') === s.key && cell.textContent === String(row[s.key])
        )
        expect(cells.length).toBeGreaterThan(0)
      }
    }
  })

  it('renders the correct value for Jan revenue', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />)
    const cells = screen.getAllByRole('cell').filter(c => c.getAttribute('data-series') === 'revenue')
    expect(cells[0].textContent).toBe('4000')
  })

  it('renders the correct value for Feb cost', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />)
    const cells = screen.getAllByRole('cell').filter(c => c.getAttribute('data-series') === 'cost')
    expect(cells[1].textContent).toBe('1398')
  })

  it('renders em dash for null/undefined values', () => {
    const data = [{ name: 'Apr', revenue: null, cost: undefined }]
    render(<ChartTableEquivalent data={data} series={SAMPLE_SERIES} caption="Monthly P&L" />)
    const cells = screen.getAllByRole('cell').filter(c => c.getAttribute('data-series') !== null)
    expect(cells[0].textContent).toBe('—')
  })
})

describe('ChartTableEquivalent — empty state', () => {
  it('renders EmptyState when data is empty', () => {
    render(<ChartTableEquivalent data={[]} series={SAMPLE_SERIES} caption="Monthly P&L" />)
    expect(screen.getByText(/no chart data/i)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('renders EmptyState when series is empty', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={[]} caption="Monthly P&L" />)
    expect(screen.getByText(/no chart data/i)).toBeInTheDocument()
  })

  it('renders EmptyState for single data point without crashing', () => {
    render(<ChartTableEquivalent data={[{ name: 'Only', value: 1 }]} series={[{ key: 'value', label: 'Value' }]} caption="Single" />)
    expect(screen.getByRole('table')).toBeInTheDocument()
  })
})

describe('ChartTableEquivalent — accessibility', () => {
  it('has scope="col" on all column headers', () => {
    const { container } = render(
      <ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />
    )
    const colHeaders = Array.from(container.querySelectorAll('th[scope="col"]'))
    expect(colHeaders).toHaveLength(3) // Name + Revenue + Cost
  })

  it('has scope="row" on row header cells', () => {
    const { container } = render(
      <ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Monthly P&L" />
    )
    const rowHeaders = Array.from(container.querySelectorAll('th[scope="row"]'))
    expect(rowHeaders).toHaveLength(SAMPLE_DATA.length)
    expect(rowHeaders[0].textContent).toBe('Jan')
  })

  it('is inside a region with an accessible label', () => {
    render(<ChartTableEquivalent data={SAMPLE_DATA} series={SAMPLE_SERIES} caption="Revenue Region" />)
    expect(screen.getByRole('region', { name: 'Revenue Region' })).toBeInTheDocument()
  })
})
