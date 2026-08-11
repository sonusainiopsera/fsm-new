import { render, screen, fireEvent } from '@testing-library/react'
import { PageHeader } from './PageHeader.jsx'

describe('PageHeader', () => {
  const defaultProps = {
    title: 'Work Orders',
    breadcrumbs: [{ label: 'Home', href: '#' }, { label: 'Work Orders' }],
  }

  it('renders title', () => {
    render(<PageHeader {...defaultProps} />)
    expect(screen.getByText('Work Orders')).toBeInTheDocument()
  })

  it('renders breadcrumb nav', () => {
    render(<PageHeader {...defaultProps} />)
    expect(screen.getByRole('navigation', { name: /breadcrumb/i })).toBeInTheDocument()
    expect(screen.getByText('Home')).toBeInTheDocument()
  })

  it('renders primary action button', () => {
    const onClick = vi.fn()
    render(<PageHeader {...defaultProps} primaryAction={{ label: 'New WO', onClick }} />)
    const btn = screen.getByRole('button', { name: 'New WO' })
    fireEvent.click(btn)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('throws when more than one primary action passed', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() =>
      render(
        <PageHeader
          {...defaultProps}
          primaryActions={[
            { label: 'A', onClick: () => {} },
            { label: 'B', onClick: () => {} },
          ]}
        />
      )
    ).toThrow()
    spy.mockRestore()
  })

  it('renders secondary action buttons', () => {
    render(
      <PageHeader
        {...defaultProps}
        secondaryActions={[
          { label: 'Export', onClick: () => {} },
          { label: 'Filter', onClick: () => {} },
        ]}
      />
    )
    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Filter' })).toBeInTheDocument()
  })
})
