import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';

import { Button } from '../Button/Button.jsx';

describe('Button', () => {
  it('renders children', () => {
    render(<Button>Click me</Button>);
    expect(screen.getByRole('button', { name: 'Click me' })).toBeInTheDocument();
  });

  it('applies variant class', () => {
    render(<Button variant="destructive">Delete</Button>);
    const btn = screen.getByRole('button');
    expect(btn.className).toMatch(/destructive/);
  });

  it('is disabled when disabled prop set', () => {
    render(<Button disabled>Disabled</Button>);
    expect(screen.getByRole('button')).toBeDisabled();
  });

  it('shows spinner and sets aria-busy when loading', () => {
    render(<Button loading>Loading</Button>);
    const btn = screen.getByRole('button');
    expect(btn).toHaveAttribute('aria-busy', 'true');
    expect(btn.querySelector('[aria-hidden]')).toBeInTheDocument();
  });

  it('calls onClick when clicked', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Click</Button>);
    await userEvent.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not call onClick when disabled', async () => {
    const onClick = vi.fn();
    render(<Button disabled onClick={onClick}>Disabled</Button>);
    await userEvent.click(screen.getByRole('button'), { pointerEventsCheck: 0 });
    expect(onClick).not.toHaveBeenCalled();
  });

  it('applies touch variant class when touch prop set', () => {
    render(<Button touch>Touch</Button>);
    expect(screen.getByRole('button').className).toMatch(/touch/);
  });

  it('warns for unknown variant', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(<Button variant="super-primary">Bad</Button>);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('unknown variant'));
    warn.mockRestore();
  });
});
