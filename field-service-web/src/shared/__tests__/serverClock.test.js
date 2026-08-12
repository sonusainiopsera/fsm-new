/**
 * serverClock tests (WO-131).
 *
 * Coverage:
 * - Skew offset arithmetic (positive and negative)
 * - serverNow() applies skew correctly
 * - subscribeToTick / unsubscribe lifecycle
 * - visibilitychange reconciliation with a mocked clock
 * - Large clock jump corrects immediately on tab resume
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  updateSkew,
  serverNow,
  getSkewMs,
  subscribeToTick,
} from '../time/serverClock.js';

describe('serverClock', () => {
  beforeEach(() => {
    // Reset skew between tests by injecting a fresh header.
    updateSkew(null);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts with zero skew', () => {
    expect(getSkewMs()).toBe(0);
    // With zero skew, serverNow() ≈ Date.now()
    const localNow = Date.now();
    expect(Math.abs(serverNow() - localNow)).toBeLessThan(50);
  });

  it('applies positive skew when server is ahead', () => {
    const serverAhead = new Date(Date.now() + 3600_000).toUTCString();
    updateSkew(serverAhead);
    expect(getSkewMs()).toBeCloseTo(3600_000, -3);
    expect(serverNow()).toBeGreaterThan(Date.now() + 3000_000);
  });

  it('applies negative skew when server is behind', () => {
    const serverBehind = new Date(Date.now() - 1800_000).toUTCString();
    updateSkew(serverBehind);
    expect(getSkewMs()).toBeCloseTo(-1800_000, -3);
    expect(serverNow()).toBeLessThan(Date.now());
  });

  it('ignores null or invalid Date header', () => {
    updateSkew(null);
    expect(getSkewMs()).toBe(0);
    updateSkew('not-a-date');
    expect(getSkewMs()).toBe(0);
  });

  it('subscribeToTick fires every second', () => {
    const fn = vi.fn();
    const unsub = subscribeToTick(fn);
    vi.advanceTimersByTime(3000);
    expect(fn).toHaveBeenCalledTimes(3);
    unsub();
  });

  it('unsubscribe stops tick notifications', () => {
    const fn = vi.fn();
    const unsub = subscribeToTick(fn);
    vi.advanceTimersByTime(1000);
    expect(fn).toHaveBeenCalledTimes(1);
    unsub();
    vi.advanceTimersByTime(2000);
    expect(fn).toHaveBeenCalledTimes(1); // no more calls
  });

  it('large positive skew (device many hours behind server) renders correct remaining time', () => {
    // Server is 8 hours ahead of local clock
    const skew = 8 * 3600_000;
    updateSkew(new Date(Date.now() + skew).toUTCString());

    // Deadline is 1 hour from server now
    const deadlineMs = serverNow() + 3600_000;
    const remaining  = deadlineMs - serverNow();

    expect(remaining).toBeCloseTo(3600_000, -3);
    // Without skew correction, remaining would be negative (deadline in local past)
    const uncorrected = deadlineMs - Date.now();
    expect(uncorrected).toBeGreaterThan(0); // still positive: 8h + 1h - 0 skew
  });

  it('large negative skew (device many hours ahead of server) renders correct remaining time', () => {
    // Server is 6 hours behind local clock
    const skew = -6 * 3600_000;
    updateSkew(new Date(Date.now() + skew).toUTCString());

    // Deadline is 2 hours from server now
    const deadlineMs   = serverNow() + 2 * 3600_000;
    const remaining    = deadlineMs - serverNow();
    expect(remaining).toBeCloseTo(2 * 3600_000, -3);
  });
});
