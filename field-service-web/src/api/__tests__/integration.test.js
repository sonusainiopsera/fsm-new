/**
 * System integration tests — end-to-end flows against the mock handler layer.
 *
 * Covers: success, 400 field errors into form, 401 refresh-and-replay,
 * 403 permission denied, 409 conflict, 422 guard refusal, 429 Retry-After,
 * 503 degraded, SSE invalidation, ETag/304 handling, pagination clamping.
 */

import { describe, it, expect, beforeAll, afterEach, afterAll, vi } from 'vitest';
import {
  installHandlers,
  resetHandlers,
  uninstallHandlers,
  mockRespond,
  errorFixture,
  seedEtag,
} from '../../mocks/handlers/index.js';
import { get, post } from '../http.js';
import { tokenStore } from '../tokenStore.js';
import { mapErrorToState } from '../stateMapping.js';
import { handleSseEvent, getInvalidationKeys } from '../eventKeyMap.js';
import { queryClient } from '../queryClient.js';
import { buildPageParams } from '../pagination.js';

beforeAll(() => installHandlers());
afterEach(() => { resetHandlers(); tokenStore.reset(); });
afterAll(() => uninstallHandlers());

describe('Success flow', () => {
  it('GET /work-orders returns paginated data', async () => {
    const result = await get('/work-orders');
    expect(result).toBeDefined();
    expect(Array.isArray(result.data)).toBe(true);
  });
});

describe('400 field errors into form', () => {
  it('exposes fieldErrors for inline validation rendering', async () => {
    const fieldErrors = [
      { field: 'lines[0].quantity', message: 'Must be positive' },
      { field: 'lines[0].partId', message: 'Required' },
    ];
    mockRespond('POST', '/api/v1/work-orders/wo-1/parts', {
      ...errorFixture(400, 'BAD_REQUEST', 'Validation failed', fieldErrors),
    });

    let caught;
    try {
      await post('/work-orders/wo-1/parts', { lines: [] });
    } catch (e) {
      caught = e;
    }

    expect(caught?.status).toBe(400);
    expect(caught?.fieldErrors).toHaveLength(2);
    expect(caught?.fieldErrors[0].field).toBe('lines[0].quantity');
    // Both errors on a field are preserved
    expect(caught?.fieldErrors[1].field).toBe('lines[0].partId');
  });
});

describe('401 silent refresh and replay', () => {
  it('refreshes once and replays all ten concurrent 401 failures', async () => {
    // Temporarily replace the mock handler with a custom fetch
    const savedFetch = globalThis.fetch;
    let refreshCount = 0;
    globalThis.fetch = vi.fn(async (url) => {
      if (url.includes('/auth/refresh')) {
        refreshCount++;
        return new Response(JSON.stringify({ accessToken: 'new-token', expiresIn: 900 }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      if (tokenStore.get() === 'new-token') {
        return new Response(JSON.stringify({ data: 'refreshed' }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: 'expired' }), {
        status: 401, headers: { 'Content-Type': 'application/json' },
      });
    });

    try {
      const requests = Array.from({ length: 10 }, () => get('/work-orders'));
      const results = await Promise.all(requests);
      expect(refreshCount).toBe(1);
      expect(results.every(r => r?.data === 'refreshed')).toBe(true);
    } finally {
      globalThis.fetch = savedFetch;
    }
  });
});

describe('403 permission denied', () => {
  it('maps to permission-denied state', async () => {
    mockRespond('GET', '/api/v1/work-orders/wo-secret', errorFixture(403));

    let caught;
    try { await get('/work-orders/wo-secret'); } catch (e) { caught = e; }

    expect(caught?.status).toBe(403);
    expect(mapErrorToState(caught)).toBe('permission-denied');
  });
});

describe('409 conflict', () => {
  it('maps to error state with server message', async () => {
    mockRespond('POST', '/api/v1/work-orders/wo-1/transitions',
      errorFixture(409, 'WORK_ORDER_ILLEGAL_TRANSITION', 'Cannot transition from CLOSED'));

    let caught;
    try { await post('/work-orders/wo-1/transitions', { event: 'ASSIGN' }); } catch (e) { caught = e; }

    expect(caught?.status).toBe(409);
    expect(mapErrorToState(caught)).toBe('error');
    expect(caught?.message).toBe('Cannot transition from CLOSED');
  });
});

describe('422 guard refusal', () => {
  it('maps to error state and is not retryable', async () => {
    mockRespond('POST', '/api/v1/work-orders/wo-1/parts',
      errorFixture(422, 'INSUFFICIENT_STOCK', 'Not enough stock', [
        { field: 'lines[0].quantity', message: 'requested 5, available 1' },
      ]));

    let caught;
    try { await post('/work-orders/wo-1/parts', { lines: [] }); } catch (e) { caught = e; }

    expect(caught?.status).toBe(422);
    expect(caught?.retryable).toBe(false);
    expect(mapErrorToState(caught)).toBe('error');
  });
});

describe('429 with Retry-After', () => {
  it('maps to degraded state and parses retryAfterMs', async () => {
    mockRespond('GET', '/api/v1/work-orders', {
      ...errorFixture(429, 'RATE_LIMITED', 'Too many requests'),
      headers: { 'Retry-After': '30', 'Content-Type': 'application/json' },
    });

    let caught;
    try { await get('/work-orders'); } catch (e) { caught = e; }

    expect(caught?.status).toBe(429);
    expect(caught?.retryAfterMs).toBe(30_000);
    expect(mapErrorToState(caught)).toBe('degraded');
  });
});

describe('503 degraded', () => {
  it('maps to degraded state and is retryable', async () => {
    mockRespond('GET', '/api/v1/work-orders', errorFixture(503));

    let caught;
    try { await get('/work-orders'); } catch (e) { caught = e; }

    expect(caught?.status).toBe(503);
    expect(caught?.retryable).toBe(true);
    expect(mapErrorToState(caught)).toBe('degraded');
  });
});

describe('ETag / 304 conditional polling', () => {
  it('returns 304 sentinel when server has not changed', async () => {
    const etag = '"test-etag-v1"';
    seedEtag('/api/v1/work-orders', etag);

    // Add ETag header so the mock returns 304
    const result = await get('/work-orders', { headers: { 'If-None-Match': etag } });
    expect(result?._304).toBe(true);
  });
});

describe('Pagination clamping', () => {
  it('clamps page size above 50 before dispatch', () => {
    const params = buildPageParams({ pageSize: 999 });
    expect(Number(params.get('size'))).toBe(50);
  });
});

describe('SSE event → query invalidation', () => {
  it('invalidates work-orders keys on at-risk event', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined);

    handleSseEvent({ type: 'at-risk', data: '' });

    const calledKeys = invalidateSpy.mock.calls.map(c => c[0].queryKey);
    const hasWorkOrders = calledKeys.some(k => Array.isArray(k) && k[0] === 'work-orders');
    expect(hasWorkOrders).toBe(true);

    invalidateSpy.mockRestore();
  });
});
