import React from 'react';
import { ErrorState } from '../components/index.js';

/**
 * Route-level error boundary.
 *
 * Renders the ErrorState primitive with the traceId from the structured
 * API error envelope when available. Never renders a stack trace or internal
 * detail (A10 — no inadvertent data disclosure through error messages).
 *
 * Used by AppShell to wrap the route Outlet, and can be used standalone
 * around any subtree that might throw.
 */
export class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, traceId: null };
  }

  static getDerivedStateFromError(error) {
    const traceId = error?.traceId ?? error?.response?.data?.traceId ?? null;
    return { hasError: true, traceId };
  }

  componentDidCatch(error, errorInfo) {
    if (process.env.NODE_ENV !== 'production') {
      console.error('[ErrorBoundary]', error, errorInfo);
    }
  }

  render() {
    if (!this.state.hasError) {
      return this.props.children;
    }

    const description = this.state.traceId
      ? `Reference: ${this.state.traceId}`
      : 'Please try refreshing the page.';

    return (
      <ErrorState
        onRetry={this.props.onReset ? () => {
          this.setState({ hasError: false, traceId: null });
          this.props.onReset?.();
        } : undefined}
        description={description}
      />
    );
  }
}

/**
 * Thin wrapper that resets the boundary when the route location changes.
 * Pass as the `errorElement` prop in React Router 6 data router configuration.
 *
 * @param {{ onReset?: () => void, children?: import('react').ReactNode }} props
 */
export function RouteErrorFallback({ onReset, children }) {
  return (
    <ErrorBoundary onReset={onReset}>
      {children}
    </ErrorBoundary>
  );
}
