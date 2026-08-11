import { render, screen, fireEvent } from '@testing-library/react'
import { DetailDrawer } from './DetailDrawer.jsx'

describe('DetailDrawer', () => {
  it('does not render when closed', () => {
    render(<DetailDrawer open={false} onClose={() => {}} title="Details"><p>Content</p></DetailDrawer>)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('renders when open', () => {
    render(<DetailDrawer open={true} onClose={() => {}} title="Details"><p>Content</p></DetailDrawer>)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('renders title', () => {
    render(<DetailDrawer open={true} onClose={() => {}} title="Work Order 42"><p>x</p></DetailDrawer>)
    expect(screen.getByText('Work Order 42')).toBeInTheDocument()
  })

  it('has aria-modal=true', () => {
    render(<DetailDrawer open={true} onClose={() => {}} title="Test"><p>x</p></DetailDrawer>)
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true')
  })

  it('calls onClose when Escape key pressed', () => {
    const onClose = vi.fn()
    render(<DetailDrawer open={true} onClose={onClose} title="Test"><p>x</p></DetailDrawer>)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('calls onClose when close button clicked', () => {
    const onClose = vi.fn()
    render(<DetailDrawer open={true} onClose={onClose} title="Test"><p>x</p></DetailDrawer>)
    fireEvent.click(screen.getByRole('button', { name: /close/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('renders children content', () => {
    render(<DetailDrawer open={true} onClose={() => {}} title="T"><p>Drawer content here</p></DetailDrawer>)
    expect(screen.getByText('Drawer content here')).toBeInTheDocument()
  })
})
