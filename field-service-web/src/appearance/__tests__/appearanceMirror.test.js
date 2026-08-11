import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { readMirror, writeMirror } from '../appearanceMirror.js';

describe('appearanceMirror', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('readMirror', () => {
    it('returns null when nothing is stored', () => {
      expect(readMirror()).toBeNull();
    });

    it('returns stored light value', () => {
      localStorage.setItem('fsvc_appearance', 'light');
      expect(readMirror()).toBe('light');
    });

    it('returns stored dark value', () => {
      localStorage.setItem('fsvc_appearance', 'dark');
      expect(readMirror()).toBe('dark');
    });

    it('returns stored system value', () => {
      localStorage.setItem('fsvc_appearance', 'system');
      expect(readMirror()).toBe('system');
    });

    it('returns null for unknown value', () => {
      localStorage.setItem('fsvc_appearance', 'turbo_neon');
      expect(readMirror()).toBeNull();
    });

    it('swallows SecurityError and returns null', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementationOnce(() => {
        throw new DOMException('Blocked', 'SecurityError');
      });
      expect(readMirror()).toBeNull();
    });
  });

  describe('writeMirror', () => {
    it('writes a valid value to localStorage', () => {
      writeMirror('dark');
      expect(localStorage.getItem('fsvc_appearance')).toBe('dark');
    });

    it('removes the key when called with null', () => {
      localStorage.setItem('fsvc_appearance', 'dark');
      writeMirror(null);
      expect(localStorage.getItem('fsvc_appearance')).toBeNull();
    });

    it('removes the key when called with undefined', () => {
      localStorage.setItem('fsvc_appearance', 'dark');
      writeMirror(undefined);
      expect(localStorage.getItem('fsvc_appearance')).toBeNull();
    });

    it('swallows SecurityError on write', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
        throw new DOMException('Blocked', 'SecurityError');
      });
      expect(() => writeMirror('dark')).not.toThrow();
    });
  });
});
