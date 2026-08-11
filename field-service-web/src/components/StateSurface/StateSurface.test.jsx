import { render, screen, fireEvent } from '@testing-library/react'
import {
  EmptyState, LoadingState, DegradedState, PermissionDeniedState, ErrorState,
  StateSurface,
} from './StateSurface.jsx'

describe('StateSurface', () => {
  it('renders EmptyState with heading', () => {
    render(<EmptyState />)
    expect(screen.getByText(/no items found/i)).toBeInTheDocument()
  })

  it('renders LoadingState as skeleton on first load with aria-label', () => {
    render(<LoadingState />)
    expect(screen.getByRole('status')).toHaveAttribute('aria-label', 'Loading content')
  })

  it('renders LoadingState as spinner when isRefetching', () => {
    render(<LoadingState isRefetching />)
    expect(screen.getByRole('status')).toHaveAttribute('aria-label', 'Updating…')
  })

  it('renders DegradedState with retry button', () => {
    const onRetry = vi.fn()
    render(<DegradedState onRetry={onRetry} />)
    const btn = screen.getByRole('button', { name: /try again/i })
    fireEvent.click(btn)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('renders PermissionDeniedState with access denied text', () => {
    render(<PermissionDeniedState />)
    expect(screen.getByText(/access denied/i)).toBeInTheDocument()
  })

  it('renders ErrorState with retry button', () => {
    const onRetry = vi.fn()
    render(<ErrorState onRetry={onRetry} />)
    const btn = screen.getByRole('button', { name: /try again/i })
    fireEvent.click(btn)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('warns and falls back gracefully for unknown variant', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    render(<StateSurface variant="unknown_variant" />)
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('Unknown variant'))
    warn.mockRestore()
  })

  it('renders custom message when provided', () => {
    render(<ErrorState onRetry={() => {}} message="Custom error text" />)
    expect(screen.getByText('Custom error text')).toBeInTheDocument()
  })
})
