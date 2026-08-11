import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { resolveAppearance, resolveFromMirror } from '../resolveAppearance.js';

function mockDarkMedia(prefersDark) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation(query => ({
      matches: prefersDark && query === '(prefers-color-scheme: dark)',
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

describe('resolveAppearance', () => {
  beforeEach(() => mockDarkMedia(false));

  it('resolves "dark" → dark', () => {
    expect(resolveAppearance('dark')).toBe('dark');
  });

  it('resolves "DARK" (API enum) → dark', () => {
    expect(resolveAppearance('DARK')).toBe('dark');
  });

  it('resolves "light" → light', () => {
    expect(resolveAppearance('light')).toBe('light');
  });

  it('resolves "LIGHT" (API enum) → light', () => {
    expect(resolveAppearance('LIGHT')).toBe('light');
  });

  it('resolves "system" → light when OS prefers light', () => {
    mockDarkMedia(false);
    expect(resolveAppearance('system')).toBe('light');
  });

  it('resolves "system" → dark when OS prefers dark', () => {
    mockDarkMedia(true);
    expect(resolveAppearance('system')).toBe('dark');
  });

  it('resolves null → light (default)', () => {
    expect(resolveAppearance(null)).toBe('light');
  });

  it('resolves undefined → light (default)', () => {
    expect(resolveAppearance(undefined)).toBe('light');
  });

  it('resolves unknown value → light', () => {
    expect(resolveAppearance('turbo_neon')).toBe('light');
  });
});

describe('resolveFromMirror', () => {
  beforeEach(() => {
    localStorage.clear();
    mockDarkMedia(false);
  });

  it('returns light when nothing is stored', () => {
    expect(resolveFromMirror()).toBe('light');
  });

  it('returns dark when dark is stored', () => {
    localStorage.setItem('fsvc_appearance', 'dark');
    expect(resolveFromMirror()).toBe('dark');
  });

  it('returns dark when system is stored and OS is dark', () => {
    localStorage.setItem('fsvc_appearance', 'system');
    mockDarkMedia(true);
    expect(resolveFromMirror()).toBe('dark');
  });
});
