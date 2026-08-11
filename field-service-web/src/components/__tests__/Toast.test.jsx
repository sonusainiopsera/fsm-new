import React from 'react';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

import { ToastProvider, useToast } from '../Toast/ToastProvider.jsx';

function ShowButton({ variant, message, detail }) {
  const { show } = useToast();
  return (
    <button type="button" onClick={() => show({ variant, message, detail })}>
      Show {variant}
    </button>
  );
}

function TestApp({ variant = 'info' }) {
  return (
    <ToastProvider>
      <ShowButton variant={variant} message="Test message" detail="Extra detail" />
    </ToastProvider>
  );
}

describe('ToastProvider', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('renders toast when show() is called', async () => {
    render(<TestApp />);
    await userEvent.click(screen.getByRole('button', { name: 'Show info' }));
    expect(screen.getByText('Test message')).toBeInTheDocument();
    expect(screen.getByText('Extra detail')).toBeInTheDocument();
  });

  it('auto-dismisses info toast after timeout', async () => {
    render(<TestApp />);
    await userEvent.click(screen.getByRole('button', { name: 'Show info' }));
    expect(screen.getByText('Test message')).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(5001); });
    expect(screen.queryByText('Test message')).not.toBeInTheDocument();
  });

  it('does not auto-dismiss danger toast', async () => {
    render(<TestApp variant="danger" />);
    await userEvent.click(screen.getByRole('button', { name: 'Show danger' }));
    act(() => { vi.advanceTimersByTime(10000); });
    expect(screen.getByText('Test message')).toBeInTheDocument();
  });

  it('shows at most one info toast (throttle)', async () => {
    render(<TestApp />);
    await userEvent.click(screen.getByRole('button', { name: 'Show info' }));
    await userEvent.click(screen.getByRole('button', { name: 'Show info' }));
    expect(screen.getAllByText('Test message')).toHaveLength(1);
  });

  it('manually dismisses toast via dismiss button', async () => {
    render(<TestApp />);
    await userEvent.click(screen.getByRole('button', { name: 'Show info' }));
    const dismissBtn = screen.getByRole('button', { name: /Dismiss/ });
    await userEvent.click(dismissBtn);
    expect(screen.queryByText('Test message')).not.toBeInTheDocument();
  });

  it('danger toast uses role=alert', async () => {
    render(<TestApp variant="danger" />);
    await userEvent.click(screen.getByRole('button', { name: 'Show danger' }));
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('info toast uses role=status', async () => {
    render(<TestApp />);
    await userEvent.click(screen.getByRole('button', { name: 'Show info' }));
    const status = screen.getByRole('status');
    expect(status).toBeInTheDocument();
  });
});
