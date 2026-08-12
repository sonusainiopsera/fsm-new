import { describe, it, expect, beforeEach } from 'vitest';
import {
  readUserAppearance,
  writeUserAppearance,
  clearUserAppearance,
  resolveUserAppearance,
} from '../../../shared/theme/appearance.js';

describe('per-user appearance persistence', () => {
  const USER_ID = 'user-tech-001';

  beforeEach(() => {
    localStorage.clear();
  });

  it('returns null when no preference is stored', () => {
    expect(readUserAppearance(USER_ID)).toBeNull();
  });

  it('writes and reads back "dark"', () => {
    writeUserAppearance(USER_ID, 'dark');
    expect(readUserAppearance(USER_ID)).toBe('dark');
  });

  it('writes and reads back "light"', () => {
    writeUserAppearance(USER_ID, 'light');
    expect(readUserAppearance(USER_ID)).toBe('light');
  });

  it('writes and reads back "system"', () => {
    writeUserAppearance(USER_ID, 'system');
    expect(readUserAppearance(USER_ID)).toBe('system');
  });

  it('clearUserAppearance removes the stored preference', () => {
    writeUserAppearance(USER_ID, 'dark');
    clearUserAppearance(USER_ID);
    expect(readUserAppearance(USER_ID)).toBeNull();
  });

  it('isolates preferences by user ID', () => {
    writeUserAppearance('user-a', 'dark');
    writeUserAppearance('user-b', 'light');
    expect(readUserAppearance('user-a')).toBe('dark');
    expect(readUserAppearance('user-b')).toBe('light');
  });

  it('falls back to the global key when userId is null', () => {
    writeUserAppearance(null, 'dark');
    expect(readUserAppearance(null)).toBe('dark');
    // Should NOT appear under a user-scoped key
    expect(readUserAppearance('some-user')).toBeNull();
  });

  it('ignores corrupt / unknown stored values', () => {
    localStorage.setItem(`fsvc_appearance_u_${USER_ID}`, 'invalid-value');
    expect(readUserAppearance(USER_ID)).toBeNull();
  });
});

describe('resolveUserAppearance', () => {
  it('resolves "dark" to "dark"', () => {
    expect(resolveUserAppearance('dark')).toBe('dark');
  });

  it('resolves "light" to "light"', () => {
    expect(resolveUserAppearance('light')).toBe('light');
  });

  it('resolves null to "light" (design-rule default)', () => {
    expect(resolveUserAppearance(null)).toBe('light');
  });

  it('resolves unknown string to "light"', () => {
    expect(resolveUserAppearance('rainbow')).toBe('light');
  });

  it('resolves "DARK" case-insensitively to "dark"', () => {
    expect(resolveUserAppearance('DARK')).toBe('dark');
  });
});
