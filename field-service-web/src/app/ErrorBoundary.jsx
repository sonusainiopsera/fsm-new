/**
 * @fileoverview Route-level and component-level error boundary.
 *
 * A10: Stack traces and internal implementation details are NEVER rendered to
 * the client. Only a traceId from the structured error envelope is surfaced to
 * allow support correlation without leaking internals.
 */
import { Component } from 'react'
import { ErrorState, PermissionDeniedState } from '../components/index.js'

/**
 * Class-based error boundary. Use as:
 *   <ErrorBoundary>{children}</ErrorBoundary>
 *
 * Also accepts an `error` prop for use as a React Router errorElement wrapper —
 * pass the error from useRouteError() as a prop when the router has already
 * caught it.
 */
export class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, caughtError: null }
    this.reset = this.reset.bind(this)
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, caughtError: error }
  }

  componentDidCatch(error) {
    // A10: never log stack trace in production-facing output
    if (typeof console !== 'undefined') {
      console.error('[ErrorBoundary] Unhandled error', {
        traceId: error?.traceId ?? null,
        type: error?.constructor?.name,
      })
    }
  }

  reset() {
    this.setState({ hasError: false, caughtError: null })
  }

  render() {
    const errorToRender = this.state.hasError
      ? this.state.caughtError
      : (this.props.error ?? null)

    if (errorToRender) {
      if (errorToRender?.status === 403) {
        return <PermissionDeniedState />
      }

      const traceId = errorToRender?.traceId ?? null
      return (
        <ErrorState
          message={traceId ? `Something went wrong. Reference: ${traceId}` : undefined}
          onRetry={this.reset}
        />
      )
    }

    return this.props.children ?? null
  }
}

/**
 * Thin wrapper for use as React Router `errorElement`.
 * Reads the route error and delegates rendering to ErrorBoundary.
 * Import useRouteError lazily to avoid hard-wiring react-router-dom at
 * the boundary level for tests.
 *
 * @param {{ error?: unknown }} props
 */
export function RouteErrorElement({ error }) {
  return <ErrorBoundary error={error} />
}
