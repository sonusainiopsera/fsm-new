import { render, screen } from '@testing-library/react'
import { Chip } from './Chip.jsx'

describe('Chip', () => {
  describe('priority kind', () => {
    it.each(['critical', 'high', 'medium', 'low'])('renders %s priority', (value) => {
      render(<Chip kind="priority" value={value} />)
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', `priority: ${value}`)
    })

    it('shows icon and text label for greyscale support', () => {
      render(<Chip kind="priority" value="critical" />)
      const chip = screen.getByRole('status')
      expect(chip.textContent).toMatch(/critical/i)
    })
  })

  describe('state kind', () => {
    it.each(['new', 'assigned', 'en_route', 'in_progress', 'on_hold', 'completed', 'closed', 'cancelled'])(
      'renders state %s',
      (value) => {
        render(<Chip kind="state" value={value} />)
        expect(screen.getByRole('status')).toBeInTheDocument()
      }
    )
  })

  describe('risk kind', () => {
    it.each(['high', 'medium', 'low'])('renders risk %s', (value) => {
      render(<Chip kind="risk" value={value} />)
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', `risk: ${value}`)
    })
  })

  it('falls back gracefully for unknown value and warns', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    render(<Chip kind="state" value="unknown_state" />)
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})
