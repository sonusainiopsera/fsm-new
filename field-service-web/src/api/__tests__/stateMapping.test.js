import { describe, it, expect } from 'vitest';
import { mapErrorToState, getQueryState } from '../stateMapping.js';
import { ClientError } from '../errors.js';

describe('mapErrorToState', () => {
  it('maps 403 to permission-denied', () => {
    const err = new ClientError(403, 'FORBIDDEN', 'denied');
    expect(mapErrorToState(err)).toBe('permission-denied');
  });

  it('maps 429 to degraded (rate-limited treated as degraded surface)', () => {
    const err = new ClientError(429, 'RATE_LIMITED', 'slow');
    expect(mapErrorToState(err)).toBe('degraded');
  });

  it('maps 503 to degraded', () => {
    const err = new ClientError(503, 'SERVICE_UNAVAILABLE', 'down');
    expect(mapErrorToState(err)).toBe('degraded');
  });

  it('maps 500 to degraded', () => {
    const err = new ClientError(500, 'SERVER_ERROR', 'internal');
    expect(mapErrorToState(err)).toBe('degraded');
  });

  it('maps network error (status 0) to degraded', () => {
    const err = new ClientError(0, 'NETWORK_ERROR', 'offline');
    expect(mapErrorToState(err)).toBe('degraded');
  });

  it('maps 409 to error (domain conflict)', () => {
    const err = new ClientError(409, 'WORK_ORDER_ILLEGAL_TRANSITION', 'bad state');
    expect(mapErrorToState(err)).toBe('error');
  });

  it('maps 422 to error (business-guard refusal)', () => {
    const err = new ClientError(422, 'INSUFFICIENT_STOCK', 'no parts');
    expect(mapErrorToState(err)).toBe('error');
  });

  it('maps 400 to error', () => {
    const err = new ClientError(400, 'BAD_REQUEST', 'invalid');
    expect(mapErrorToState(err)).toBe('error');
  });

  it('maps 404 to error', () => {
    const err = new ClientError(404, 'NOT_FOUND', 'missing');
    expect(mapErrorToState(err)).toBe('error');
  });

  it('returns degraded when no error and data is stale beyond threshold', () => {
    const staleTs = Date.now() - 120_000; // 2 minutes ago
    expect(mapErrorToState(null, { staleBeyondMs: 60_000, lastFetchedAt: staleTs })).toBe('degraded');
  });
});

describe('getQueryState', () => {
  it('returns loading when isLoading and isFetching', () => {
    const result = getQueryState({ isLoading: true, isFetching: true, isError: false, error: null, dataUpdatedAt: 0 });
    expect(result).toBe('loading');
  });

  it('returns error mapped state when isError', () => {
    const err = new ClientError(403, 'FORBIDDEN', 'denied');
    const result = getQueryState({ isLoading: false, isFetching: false, isError: true, error: err, dataUpdatedAt: 0 });
    expect(result).toBe('permission-denied');
  });

  it('returns degraded when data is stale beyond staleBeyondMs', () => {
    const staleTs = Date.now() - 120_000;
    const result = getQueryState(
      { isLoading: false, isFetching: false, isError: false, error: null, dataUpdatedAt: staleTs },
      { staleBeyondMs: 60_000 }
    );
    expect(result).toBe('degraded');
  });

  it('returns null for healthy recent data', () => {
    const result = getQueryState(
      { isLoading: false, isFetching: false, isError: false, error: null, dataUpdatedAt: Date.now() },
      { staleBeyondMs: 60_000 }
    );
    expect(result).toBeNull();
  });
});
