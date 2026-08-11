import { render, screen, fireEvent } from '@testing-library/react'
import { Button } from './Button.jsx'

describe('Button', () => {
  it('renders children', () => {
    render(<Button>Click me</Button>)
    expect(screen.getByRole('button', { name: 'Click me' })).toBeInTheDocument()
  })

  it('applies default primary variant', () => {
    render(<Button>Primary</Button>)
    expect(screen.getByRole('button')).toHaveAttribute('data-variant', 'primary')
  })

  it.each(['primary', 'secondary', 'tertiary', 'ghost', 'destructive'])('renders variant %s', (variant) => {
    render(<Button variant={variant}>{variant}</Button>)
    expect(screen.getByRole('button')).toHaveAttribute('data-variant', variant)
  })

  it('warns on unknown variant', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    render(<Button variant="unknown">bad</Button>)
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('Unknown variant'))
    warn.mockRestore()
  })

  it('calls onClick handler', () => {
    const handler = vi.fn()
    render(<Button onClick={handler}>Click</Button>)
    fireEvent.click(screen.getByRole('button'))
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('is disabled when disabled prop set', () => {
    render(<Button disabled>Disabled</Button>)
    expect(screen.getByRole('button')).toBeDisabled()
  })

  it('renders 44 px touch target when touchTarget prop set', () => {
    render(<Button touchTarget>Touch</Button>)
    expect(screen.getByRole('button')).toHaveAttribute('data-touch-target', 'true')
  })

  it('renders as submit when type=submit', () => {
    render(<Button type="submit">Submit</Button>)
    expect(screen.getByRole('button')).toHaveAttribute('type', 'submit')
  })
})
