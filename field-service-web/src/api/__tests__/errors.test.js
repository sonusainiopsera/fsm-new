import { describe, it, expect } from 'vitest';
import { ClientError, normalizeError, networkError } from '../errors.js';

describe('ClientError', () => {
  it('sets retryable=false for every 4xx status', () => {
    const statuses = [400, 401, 403, 404, 409, 422, 429];
    for (const status of statuses) {
      const err = new ClientError(status, 'CODE', 'msg');
      expect(err.retryable, `status ${status} should not be retryable`).toBe(false);
    }
  });

  it('sets retryable=true for 5xx statuses', () => {
    const statuses = [500, 502, 503, 504];
    for (const status of statuses) {
      const err = new ClientError(status, 'CODE', 'msg');
      expect(err.retryable, `status ${status} should be retryable`).toBe(true);
    }
  });

  it('sets retryable=true for network error (status 0)', () => {
    const err = new ClientError(0, 'NETWORK_ERROR', 'fail');
    expect(err.retryable).toBe(true);
  });

  it('stores fieldErrors and traceId', () => {
    const fieldErrors = [{ field: 'email', message: 'Invalid' }];
    const err = new ClientError(400, 'BAD_REQUEST', 'bad', fieldErrors, 'trace-123');
    expect(err.fieldErrors).toEqual(fieldErrors);
    expect(err.traceId).toBe('trace-123');
  });

  it('stores retryAfterMs', () => {
    const err = new ClientError(429, 'RATE_LIMITED', 'slow', [], null, 5000);
    expect(err.retryAfterMs).toBe(5000);
  });
});

describe('normalizeError', () => {
  function fakeResponse(status, body, headers = {}) {
    return {
      status,
      headers: { get: (h) => headers[h] ?? null },
      ok: status >= 200 && status < 300,
    };
  }

  it('extracts code, message, fieldErrors and traceId from envelope', () => {
    const body = {
      code: 'INSUFFICIENT_STOCK',
      message: 'Not enough parts',
      fieldErrors: [{ field: 'lines[0].quantity', message: 'requested 5, available 1' }],
      traceId: 'abc',
    };
    const err = normalizeError(fakeResponse(422, {}), body);
    expect(err.status).toBe(422);
    expect(err.code).toBe('INSUFFICIENT_STOCK');
    expect(err.message).toBe('Not enough parts');
    expect(err.fieldErrors).toHaveLength(1);
    expect(err.traceId).toBe('abc');
    expect(err.retryable).toBe(false);
  });

  it('uses default code and message when body is missing fields', () => {
    const err = normalizeError(fakeResponse(503, {}), null);
    expect(err.code).toBe('SERVICE_UNAVAILABLE');
    expect(err.retryable).toBe(true);
  });

  it('parses Retry-After header into retryAfterMs', () => {
    const err = normalizeError(fakeResponse(429, {}, { 'Retry-After': '10' }), null);
    expect(err.retryAfterMs).toBe(10_000);
  });

  it('maps 403 to FORBIDDEN code', () => {
    const err = normalizeError(fakeResponse(403, {}), {});
    expect(err.code).toBe('FORBIDDEN');
  });

  it('exposes multiple fieldErrors for 400', () => {
    const body = {
      code: 'BAD_REQUEST',
      message: 'Validation failed',
      fieldErrors: [
        { field: 'email', message: 'Required' },
        { field: 'email', message: 'Must be valid email' },
      ],
    };
    const err = normalizeError(fakeResponse(400, {}), body);
    expect(err.fieldErrors).toHaveLength(2);
    expect(err.fieldErrors.every(fe => fe.field === 'email')).toBe(true);
  });
});

describe('networkError', () => {
  it('wraps a fetch error with status 0 and retryable=true', () => {
    const err = networkError(new Error('Failed to fetch'));
    expect(err.status).toBe(0);
    expect(err.retryable).toBe(true);
    expect(err.code).toBe('NETWORK_ERROR');
  });
});
