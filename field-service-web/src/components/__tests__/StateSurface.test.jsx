import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';

import {
  StateSurface,
  EmptyState,
  LoadingState,
  DegradedState,
  PermissionDeniedState,
  ErrorState,
} from '../StateSurface/StateSurface.jsx';

describe('StateSurface', () => {
  it('EmptyState renders title and description', () => {
    render(<EmptyState />);
    expect(screen.getByText('Nothing here yet')).toBeInTheDocument();
  });

  it('LoadingState on first load renders skeleton', () => {
    render(<LoadingState />);
    expect(screen.getByText('Loading, please wait')).toBeInTheDocument();
    expect(document.querySelector('[class*="skeletonLine"]')).toBeInTheDocument();
  });

  it('LoadingState on refetch renders inline indicator', () => {
    render(<LoadingState isRefetch />);
    expect(screen.getByText('Updating…')).toBeInTheDocument();
    expect(document.querySelector('[class*="skeletonLine"]')).not.toBeInTheDocument();
  });

  it('DegradedState renders stale data messaging', () => {
    render(<DegradedState />);
    expect(screen.getByText('Showing stale data')).toBeInTheDocument();
  });

  it('PermissionDeniedState renders access restricted', () => {
    render(<PermissionDeniedState />);
    expect(screen.getByText('Access restricted')).toBeInTheDocument();
  });

  it('PermissionDeniedState does not render retry button', () => {
    render(<PermissionDeniedState onRetry={() => {}} />);
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument();
  });

  it('ErrorState renders retry button when onRetry provided', async () => {
    const onRetry = vi.fn();
    render(<ErrorState onRetry={onRetry} />);
    const retryBtn = screen.getByRole('button', { name: /try again/i });
    await userEvent.click(retryBtn);
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('accepts custom title and description', () => {
    render(<EmptyState title="No results" description="Try adjusting filters" />);
    expect(screen.getByText('No results')).toBeInTheDocument();
    expect(screen.getByText('Try adjusting filters')).toBeInTheDocument();
  });

  it('warns for unknown variant', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(<StateSurface variant="unknown-state" />);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('unknown variant'));
    warn.mockRestore();
  });
});
