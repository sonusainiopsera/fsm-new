/**
 * Unit tests for ErrorBoundary.
 * Tests run without react-router-dom (ErrorBoundary is a class component
 * that composes StateSurface primitives).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ErrorBoundary } from './ErrorBoundary.jsx'

function ThrowOnRender({ error }) {
  if (error) throw error
  return <div>rendered content</div>
}

describe('ErrorBoundary — normal rendering', () => {
  it('renders children when no error', () => {
    render(
      <ErrorBoundary>
        <ThrowOnRender error={null} />
      </ErrorBoundary>
    )
    expect(screen.getByText('rendered content')).toBeInTheDocument()
  })

  it('renders nothing when children is undefined', () => {
    const { container } = render(<ErrorBoundary />)
    expect(container.firstChild).toBeNull()
  })
})

describe('ErrorBoundary — error caught by getDerivedStateFromError', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('renders ErrorState when child throws', () => {
    render(
      <ErrorBoundary>
        <ThrowOnRender error={new Error('boom')} />
      </ErrorBoundary>
    )
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument()
  })

  it('never renders stack trace or internal error message (A10)', () => {
    const sensitiveMessage = 'SQL: syntax error near "DROP TABLE users"'
    const err = new Error(sensitiveMessage)
    render(
      <ErrorBoundary>
        <ThrowOnRender error={err} />
      </ErrorBoundary>
    )
    expect(screen.queryByText(sensitiveMessage)).not.toBeInTheDocument()
    expect(screen.queryByText(/SQL/)).not.toBeInTheDocument()
  })

  it('renders traceId when present in structured error', () => {
    const err = new Error('backend error')
    err.traceId = 'trace-abc-123'
    render(
      <ErrorBoundary>
        <ThrowOnRender error={err} />
      </ErrorBoundary>
    )
    expect(screen.getByText(/trace-abc-123/)).toBeInTheDocument()
  })

  it('does NOT render traceId when absent', () => {
    render(
      <ErrorBoundary>
        <ThrowOnRender error={new Error('no trace')} />
      </ErrorBoundary>
    )
    // Generic message only, no trace reference
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument()
    expect(screen.queryByText(/reference/i)).not.toBeInTheDocument()
  })

  it('renders PermissionDeniedState for status 403 errors', () => {
    const err = new Error('forbidden')
    err.status = 403
    render(
      <ErrorBoundary>
        <ThrowOnRender error={err} />
      </ErrorBoundary>
    )
    expect(screen.getByText(/access denied/i)).toBeInTheDocument()
    expect(screen.queryByText(/something went wrong/i)).not.toBeInTheDocument()
  })
})

describe('ErrorBoundary — error prop (router errorElement usage)', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('renders ErrorState when given error prop', () => {
    render(<ErrorBoundary error={new Error('route error')} />)
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument()
  })

  it('renders PermissionDeniedState for 403 route errors', () => {
    const err = { status: 403, message: 'Forbidden' }
    render(<ErrorBoundary error={err} />)
    expect(screen.getByText(/access denied/i)).toBeInTheDocument()
  })

  it('surfaces traceId from route error', () => {
    const err = { status: 500, traceId: 'route-trace-xyz', message: 'Server Error' }
    render(<ErrorBoundary error={err} />)
    expect(screen.getByText(/route-trace-xyz/)).toBeInTheDocument()
  })
})
