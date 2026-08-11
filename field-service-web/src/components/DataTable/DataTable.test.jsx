import { render, screen, fireEvent } from '@testing-library/react'
import { DataTable, DensityToggle } from './DataTable.jsx'
import { DensityProvider } from '../../density/DensityContext.js'

const COLUMNS = [
  { key: 'id', header: 'ID', sortable: true },
  { key: 'name', header: 'Name', sortable: true },
  { key: 'status', header: 'Status' },
]

const ROWS = [
  { id: 'r1', name: 'Alpha', status: 'open' },
  { id: 'r2', name: 'Beta', status: 'closed' },
  { id: 'r3', name: 'Gamma', status: 'open' },
]

function TableWrapper({ rows = ROWS, ...rest }) {
  return (
    <DensityProvider>
      <DataTable columns={COLUMNS} rows={rows} rowKey={r => r.id} aria-label="Test table" {...rest} />
    </DensityProvider>
  )
}

describe('DataTable', () => {
  it('renders column headers', () => {
    render(<TableWrapper />)
    expect(screen.getByText('ID')).toBeInTheDocument()
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
  })

  it('renders all rows', () => {
    render(<TableWrapper />)
    expect(screen.getByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('Beta')).toBeInTheDocument()
    expect(screen.getByText('Gamma')).toBeInTheDocument()
  })

  it('shows EmptyState when rows is empty', () => {
    render(<TableWrapper rows={[]} />)
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('sorts rows by column when header clicked', () => {
    render(<TableWrapper />)
    const nameHeader = screen.getByText('Name')
    fireEvent.click(nameHeader)
    const cells = screen.getAllByRole('cell').filter(c => ['Alpha', 'Beta', 'Gamma'].includes(c.textContent))
    expect(cells[0].textContent).toBe('Alpha')
    expect(cells[1].textContent).toBe('Beta')
    expect(cells[2].textContent).toBe('Gamma')
  })

  it('reverses sort on second click', () => {
    render(<TableWrapper />)
    const nameHeader = screen.getByText('Name')
    fireEvent.click(nameHeader)
    fireEvent.click(nameHeader)
    const cells = screen.getAllByRole('cell').filter(c => ['Alpha', 'Beta', 'Gamma'].includes(c.textContent))
    expect(cells[0].textContent).toBe('Gamma')
  })

  it('highlights selected row', () => {
    render(<TableWrapper selectedRowKey="r2" />)
    const rows = screen.getAllByRole('row')
    const selectedRow = rows.find(r => r.getAttribute('aria-selected') === 'true')
    expect(selectedRow).toBeDefined()
  })
})

describe('DensityToggle', () => {
  it('renders toggle button', () => {
    render(
      <DensityProvider>
        <DensityToggle />
      </DensityProvider>
    )
    expect(screen.getByRole('button')).toBeInTheDocument()
  })

  it('toggles density on click', () => {
    render(
      <DensityProvider>
        <DensityToggle />
      </DensityProvider>
    )
    const btn = screen.getByRole('button')
    const initialText = btn.textContent
    fireEvent.click(btn)
    expect(btn.textContent).not.toBe(initialText)
  })
})
