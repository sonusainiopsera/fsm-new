import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import AppearanceProvider from '../AppearanceProvider.jsx';
import { useAppearance } from '../AppearanceContext.js';

// Always start with a light data-appearance attribute on the document root
beforeEach(() => {
  document.documentElement.setAttribute('data-appearance', 'light');
  localStorage.clear();
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation(query => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
});

function TestConsumer() {
  const { appearance, setting, setSetting, toggleAppearance } = useAppearance();
  return (
    <div>
      <span data-testid="appearance">{appearance}</span>
      <span data-testid="setting">{setting}</span>
      <button onClick={toggleAppearance}>toggle</button>
      <button onClick={() => setSetting('dark')}>set-dark</button>
      <button onClick={() => setSetting('system')}>set-system</button>
    </div>
  );
}

describe('AppearanceProvider', () => {
  it('reads initial appearance from data-appearance attribute', () => {
    render(
      <AppearanceProvider>
        <TestConsumer />
      </AppearanceProvider>
    );
    expect(screen.getByTestId('appearance').textContent).toBe('light');
  });

  it('toggleAppearance flips light → dark and updates DOM attribute', () => {
    render(
      <AppearanceProvider>
        <TestConsumer />
      </AppearanceProvider>
    );
    fireEvent.click(screen.getByText('toggle'));
    expect(screen.getByTestId('appearance').textContent).toBe('dark');
    expect(document.documentElement.getAttribute('data-appearance')).toBe('dark');
  });

  it('toggleAppearance flips dark → light', () => {
    document.documentElement.setAttribute('data-appearance', 'dark');
    render(
      <AppearanceProvider>
        <TestConsumer />
      </AppearanceProvider>
    );
    fireEvent.click(screen.getByText('toggle'));
    expect(screen.getByTestId('appearance').textContent).toBe('light');
  });

  it('setSetting writes to localStorage mirror', () => {
    render(
      <AppearanceProvider>
        <TestConsumer />
      </AppearanceProvider>
    );
    fireEvent.click(screen.getByText('set-dark'));
    expect(localStorage.getItem('fsvc_appearance')).toBe('dark');
  });

  it('calls onPersist callback when setting changes', () => {
    const onPersist = vi.fn();
    render(
      <AppearanceProvider onPersist={onPersist}>
        <TestConsumer />
      </AppearanceProvider>
    );
    fireEvent.click(screen.getByText('set-dark'));
    expect(onPersist).toHaveBeenCalledOnce();
    expect(onPersist).toHaveBeenCalledWith('dark');
  });

  it('setting "system" resolves via OS media query (light OS)', () => {
    render(
      <AppearanceProvider>
        <TestConsumer />
      </AppearanceProvider>
    );
    fireEvent.click(screen.getByText('set-system'));
    expect(screen.getByTestId('setting').textContent).toBe('system');
    // matchMedia returns matches:false → light
    expect(screen.getByTestId('appearance').textContent).toBe('light');
  });
});
