import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useNetworkStatus, NetworkOfflineError } from '../useNetworkStatus.js';

describe('useNetworkStatus', () => {
  beforeEach(() => {
    Object.defineProperty(navigator, 'onLine', { writable: true, value: true });
  });

  it('returns isOnline=true when navigator.onLine is true', () => {
    const { result } = renderHook(() => useNetworkStatus());
    expect(result.current.isOnline).toBe(true);
    expect(result.current.isOffline).toBe(false);
  });

  it('updates isOnline to false on offline event', () => {
    const { result } = renderHook(() => useNetworkStatus());
    act(() => {
      Object.defineProperty(navigator, 'onLine', { writable: true, value: false });
      window.dispatchEvent(new Event('offline'));
    });
    expect(result.current.isOffline).toBe(true);
  });

  it('updates isOnline to true on online event', () => {
    Object.defineProperty(navigator, 'onLine', { writable: true, value: false });
    const { result } = renderHook(() => useNetworkStatus());

    act(() => {
      Object.defineProperty(navigator, 'onLine', { writable: true, value: true });
      window.dispatchEvent(new Event('online'));
    });
    expect(result.current.isOnline).toBe(true);
  });

  it('assertOnline does not throw when online', () => {
    const { result } = renderHook(() => useNetworkStatus());
    expect(() => result.current.assertOnline()).not.toThrow();
  });

  it('assertOnline throws NetworkOfflineError when offline — blocks mutation', () => {
    Object.defineProperty(navigator, 'onLine', { writable: true, value: false });
    const { result } = renderHook(() => useNetworkStatus());

    act(() => {
      window.dispatchEvent(new Event('offline'));
    });

    expect(() => result.current.assertOnline()).toThrow(NetworkOfflineError);
  });

  it('NetworkOfflineError has the correct name', () => {
    try {
      throw new NetworkOfflineError('test');
    } catch (e) {
      expect(e.name).toBe('NetworkOfflineError');
    }
  });
});
