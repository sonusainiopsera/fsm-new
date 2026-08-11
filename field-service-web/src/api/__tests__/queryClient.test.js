import { describe, it, expect } from 'vitest';
import { shouldRetry, jitteredBackoff } from '../queryClient.js';
import { ClientError } from '../errors.js';

describe('shouldRetry', () => {
  const ALL_4XX = [400, 401, 403, 404, 409, 422, 429];
  const ALL_5XX = [500, 502, 503, 504];

  it.each(ALL_4XX)('returns false for %i (client errors are never retried)', (status) => {
    const err = new ClientError(status, 'CODE', 'msg');
    expect(shouldRetry(1, err)).toBe(false);
  });

  it('returns false for 409 (illegal lifecycle transition)', () => {
    const err = new ClientError(409, 'WORK_ORDER_ILLEGAL_TRANSITION', 'illegal');
    expect(shouldRetry(1, err)).toBe(false);
  });

  it('returns false for 422 (business-guard refusal)', () => {
    const err = new ClientError(422, 'INSUFFICIENT_STOCK', 'not enough');
    expect(shouldRetry(1, err)).toBe(false);
  });

  it('returns false for 429 (rate-limited)', () => {
    const err = new ClientError(429, 'RATE_LIMITED', 'slow down');
    expect(shouldRetry(1, err)).toBe(false);
  });

  it.each(ALL_5XX)('returns true for %i on first attempt', (status) => {
    const err = new ClientError(status, 'SERVER_ERROR', 'oops');
    expect(shouldRetry(1, err)).toBe(true);
  });

  it('returns true for network error (status 0)', () => {
    const err = new ClientError(0, 'NETWORK_ERROR', 'offline');
    expect(shouldRetry(1, err)).toBe(true);
  });

  it('returns false after MAX_RETRIES (3) attempts for 5xx', () => {
    const err = new ClientError(503, 'SERVICE_UNAVAILABLE', 'down');
    expect(shouldRetry(3, err)).toBe(false);
    expect(shouldRetry(4, err)).toBe(false);
  });

  it('returns true for attempt 2 on 5xx', () => {
    const err = new ClientError(500, 'SERVER_ERROR', 'down');
    expect(shouldRetry(2, err)).toBe(true);
  });
});

describe('jitteredBackoff', () => {
  it('returns a value between 0 and 30000ms', () => {
    for (let i = 0; i <= 10; i++) {
      const delay = jitteredBackoff(i);
      expect(delay).toBeGreaterThan(0);
      expect(delay).toBeLessThanOrEqual(30_000);
    }
  });

  it('grows with attempt index (statistical: expected value increases)', () => {
    // Average over samples to reduce randomness
    const avg = (attempt, n = 100) =>
      Array.from({ length: n }, () => jitteredBackoff(attempt)).reduce((a, b) => a + b, 0) / n;
    expect(avg(0)).toBeLessThan(avg(3));
    expect(avg(3)).toBeLessThan(avg(10));
  });
});
