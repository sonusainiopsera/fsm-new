import { render, screen, fireEvent } from '@testing-library/react'
import { Modal } from './Modal.jsx'

describe('Modal', () => {
  it('does not render when closed', () => {
    render(<Modal open={false} onClose={() => {}} title="Test Modal"><p>Content</p></Modal>)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('renders when open', () => {
    render(<Modal open={true} onClose={() => {}} title="Test Modal"><p>Content</p></Modal>)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('renders title', () => {
    render(<Modal open={true} onClose={() => {}} title="My Modal"><p>Content</p></Modal>)
    expect(screen.getByText('My Modal')).toBeInTheDocument()
  })

  it('has aria-modal=true', () => {
    render(<Modal open={true} onClose={() => {}} title="Test"><p>x</p></Modal>)
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true')
  })

  it('has aria-labelledby pointing to title element', () => {
    render(<Modal open={true} onClose={() => {}} title="Titled"><p>x</p></Modal>)
    const dialog = screen.getByRole('dialog')
    const labelId = dialog.getAttribute('aria-labelledby')
    expect(document.getElementById(labelId)).not.toBeNull()
    expect(document.getElementById(labelId).textContent).toBe('Titled')
  })

  it('calls onClose when Escape key pressed', () => {
    const onClose = vi.fn()
    render(<Modal open={true} onClose={onClose} title="Test"><p>x</p></Modal>)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('calls onClose when close button clicked', () => {
    const onClose = vi.fn()
    render(<Modal open={true} onClose={onClose} title="Test"><p>x</p></Modal>)
    fireEvent.click(screen.getByRole('button', { name: /close/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('renders children inside panel', () => {
    render(<Modal open={true} onClose={() => {}} title="T"><p>Modal body text</p></Modal>)
    expect(screen.getByText('Modal body text')).toBeInTheDocument()
  })
})
