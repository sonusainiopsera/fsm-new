/**
 * Unit tests for NotConnectedBanner.
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { NotConnectedBanner } from './NotConnectedBanner.jsx'

describe('NotConnectedBanner', () => {
  it('renders nothing when isVisible is false', () => {
    const { container } = render(
      <NotConnectedBanner isVisible={false} lastConnectedAt={null} onRetry={() => {}} />
    )
    expect(container.firstChild).toBeNull()
  })

  it('renders banner when isVisible is true', () => {
    render(
      <NotConnectedBanner isVisible={true} lastConnectedAt={null} onRetry={() => {}} />
    )
    expect(screen.getByTestId('not-connected-banner')).toBeInTheDocument()
    expect(screen.getByText('Not connected')).toBeInTheDocument()
  })

  it('shows cached data age when lastConnectedAt is provided', () => {
    const lastConnectedAt = Date.now() - 5 * 60 * 1000 // 5 minutes ago
    render(
      <NotConnectedBanner
        isVisible={true}
        lastConnectedAt={lastConnectedAt}
        onRetry={() => {}}
      />
    )
    expect(screen.getByText(/5 minutes ago/)).toBeInTheDocument()
  })

  it('shows stale warning when cache is older than 12 hours', () => {
    const lastConnectedAt = Date.now() - 13 * 60 * 60 * 1000 // 13 hours ago
    render(
      <NotConnectedBanner
        isVisible={true}
        lastConnectedAt={lastConnectedAt}
        onRetry={() => {}}
      />
    )
    expect(screen.getByText(/older than 12 hours/)).toBeInTheDocument()
  })

  it('calls onRetry when retry button is clicked', () => {
    const onRetry = vi.fn()
    render(
      <NotConnectedBanner isVisible={true} lastConnectedAt={null} onRetry={onRetry} />
    )
    fireEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('has role=status and aria-live=polite for screen readers', () => {
    render(
      <NotConnectedBanner isVisible={true} lastConnectedAt={null} onRetry={() => {}} />
    )
    const banner = screen.getByRole('status')
    expect(banner).toHaveAttribute('aria-live', 'polite')
  })

  it('retry button has accessible label', () => {
    render(
      <NotConnectedBanner isVisible={true} lastConnectedAt={null} onRetry={() => {}} />
    )
    expect(screen.getByRole('button', { name: 'Retry connection' })).toBeInTheDocument()
  })
})
