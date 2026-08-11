import { describe, it, expect, beforeEach, vi } from 'vitest';
import { tokenStore } from '../tokenStore.js';

beforeEach(() => {
  tokenStore.reset();
});

describe('tokenStore', () => {
  it('initialises with null token', () => {
    expect(tokenStore.get()).toBeNull();
  });

  it('stores and retrieves a token', () => {
    tokenStore.set('tok-abc');
    expect(tokenStore.get()).toBe('tok-abc');
  });

  it('clears the token', () => {
    tokenStore.set('tok-abc');
    tokenStore.clear();
    expect(tokenStore.get()).toBeNull();
  });

  it('notifies subscribers on set', () => {
    const cb = vi.fn();
    tokenStore.subscribe(cb);
    tokenStore.set('tok-xyz');
    expect(cb).toHaveBeenCalledWith('tok-xyz');
  });

  it('notifies subscribers on clear', () => {
    const cb = vi.fn();
    tokenStore.subscribe(cb);
    tokenStore.set('tok-abc');
    tokenStore.clear();
    expect(cb).toHaveBeenLastCalledWith(null);
  });

  it('unsubscribe removes the callback', () => {
    const cb = vi.fn();
    const unsub = tokenStore.subscribe(cb);
    unsub();
    tokenStore.set('tok');
    expect(cb).not.toHaveBeenCalled();
  });

  it('never reads or writes localStorage', () => {
    const getSpy = vi.spyOn(window.localStorage, 'getItem');
    const setSpy = vi.spyOn(window.localStorage, 'setItem');
    tokenStore.set('tok');
    tokenStore.get();
    tokenStore.clear();
    expect(getSpy).not.toHaveBeenCalled();
    expect(setSpy).not.toHaveBeenCalled();
  });

  it('never reads or writes sessionStorage', () => {
    const getSpy = vi.spyOn(window.sessionStorage, 'getItem');
    const setSpy = vi.spyOn(window.sessionStorage, 'setItem');
    tokenStore.set('tok');
    tokenStore.get();
    tokenStore.clear();
    expect(getSpy).not.toHaveBeenCalled();
    expect(setSpy).not.toHaveBeenCalled();
  });

  describe('single-flight refresh', () => {
    it('calls refreshFn only once for concurrent calls', async () => {
      const refreshFn = vi.fn().mockResolvedValue('new-token');
      const [p1, p2, p3] = [tokenStore.refresh(refreshFn), tokenStore.refresh(refreshFn), tokenStore.refresh(refreshFn)];
      const results = await Promise.all([p1, p2, p3]);
      expect(refreshFn).toHaveBeenCalledTimes(1);
      expect(results).toEqual(['new-token', 'new-token', 'new-token']);
    });

    it('stores the new token on success', async () => {
      await tokenStore.refresh(() => Promise.resolve('refreshed'));
      expect(tokenStore.get()).toBe('refreshed');
    });

    it('clears the token on refresh failure', async () => {
      tokenStore.set('stale-token');
      await expect(
        tokenStore.refresh(() => Promise.reject(new Error('expired')))
      ).rejects.toThrow('expired');
      expect(tokenStore.get()).toBeNull();
    });

    it('is not refreshing after completion', async () => {
      expect(tokenStore.isRefreshing()).toBe(false);
      const p = tokenStore.refresh(() => Promise.resolve('tok'));
      expect(tokenStore.isRefreshing()).toBe(true);
      await p;
      expect(tokenStore.isRefreshing()).toBe(false);
    });
  });
});
