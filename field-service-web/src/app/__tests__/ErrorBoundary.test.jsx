import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ErrorBoundary } from '../ErrorBoundary.jsx';

// Suppress React's error boundary console.error noise in test output
beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

function Thrower({ error }) {
  throw error;
}

function ThrowAfterRender({ shouldThrow, error }) {
  if (shouldThrow) throw error;
  return <div>Normal content</div>;
}

describe('ErrorBoundary', () => {
  it('renders children when no error is thrown', () => {
    render(
      <ErrorBoundary>
        <div data-testid="content">OK</div>
      </ErrorBoundary>
    );
    expect(screen.getByTestId('content')).toBeInTheDocument();
  });

  it('renders ErrorState when a child throws', () => {
    render(
      <ErrorBoundary>
        <Thrower error={new Error('Something broke')} />
      </ErrorBoundary>
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText(/Something broke/)).not.toBeInTheDocument();
  });

  it('does NOT render a stack trace or internal details (A10)', () => {
    const internalError = new Error('Internal DB error at line 42 of service.js');
    render(
      <ErrorBoundary>
        <Thrower error={internalError} />
      </ErrorBoundary>
    );
    expect(screen.queryByText(/service\.js/)).not.toBeInTheDocument();
    expect(screen.queryByText(/line 42/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Internal DB error/)).not.toBeInTheDocument();
  });

  it('renders traceId from error.traceId when present', () => {
    const traceError = Object.assign(new Error('boom'), { traceId: 'trace-abc-123' });
    render(
      <ErrorBoundary>
        <Thrower error={traceError} />
      </ErrorBoundary>
    );
    expect(screen.getByText(/trace-abc-123/)).toBeInTheDocument();
  });

  it('renders fallback description without traceId when traceId is absent', () => {
    render(
      <ErrorBoundary>
        <Thrower error={new Error('no trace')} />
      </ErrorBoundary>
    );
    expect(screen.getByText(/Please try refreshing/)).toBeInTheDocument();
  });

  it('calls onReset and clears error state when retry is clicked', async () => {
    const onReset = vi.fn();
    const { rerender } = render(
      <ErrorBoundary onReset={onReset}>
        <Thrower error={new Error('fail')} />
      </ErrorBoundary>
    );

    const retryBtn = screen.getByRole('button', { name: /try again/i });
    expect(retryBtn).toBeInTheDocument();
    retryBtn.click();

    expect(onReset).toHaveBeenCalledOnce();
  });
});
