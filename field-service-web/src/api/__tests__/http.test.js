import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { apiFetch, get, post } from '../http.js';
import { tokenStore } from '../tokenStore.js';
import { ClientError } from '../errors.js';

// ---- fetch mock helpers ------------------------------------------------

function jsonResponse(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function noContentResponse() {
  return new Response(null, { status: 204 });
}

// ---- tests -------------------------------------------------------------

beforeEach(() => {
  tokenStore.reset();
});

describe('apiFetch', () => {
  it('attaches Authorization header when token is set', async () => {
    tokenStore.set('my-token');
    let capturedHeaders;
    globalThis.fetch = vi.fn(async (url, opts) => {
      capturedHeaders = opts.headers;
      return jsonResponse(200, { ok: true });
    });

    await get('/test');
    expect(new Headers(capturedHeaders).get('Authorization')).toBe('Bearer my-token');
  });

  it('does not attach Authorization when no token', async () => {
    let capturedHeaders;
    globalThis.fetch = vi.fn(async (url, opts) => {
      capturedHeaders = opts.headers;
      return jsonResponse(200, {});
    });

    await get('/test');
    expect(new Headers(capturedHeaders).get('Authorization')).toBeNull();
  });

  it('adds Idempotency-Key on POST', async () => {
    let capturedHeaders;
    globalThis.fetch = vi.fn(async (url, opts) => {
      capturedHeaders = opts.headers;
      return jsonResponse(200, {});
    });

    await post('/resource', { foo: 'bar' });
    expect(new Headers(capturedHeaders).get('Idempotency-Key')).toBeTruthy();
  });

  it('does not add Idempotency-Key on GET', async () => {
    let capturedHeaders;
    globalThis.fetch = vi.fn(async (url, opts) => {
      capturedHeaders = opts.headers;
      return jsonResponse(200, {});
    });

    await get('/test');
    expect(new Headers(capturedHeaders).get('Idempotency-Key')).toBeNull();
  });

  it('returns null for 204 No Content', async () => {
    globalThis.fetch = vi.fn(async () => noContentResponse());
    const result = await get('/empty');
    expect(result).toBeNull();
  });

  it('throws ClientError for 4xx responses', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse(422, { code: 'INSUFFICIENT_STOCK', message: 'not enough', fieldErrors: [], traceId: 't1' })
    );

    await expect(post('/parts', {})).rejects.toMatchObject({
      status: 422,
      code: 'INSUFFICIENT_STOCK',
      retryable: false,
    });
  });

  it('throws ClientError for 503', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse(503, { code: 'SERVICE_UNAVAILABLE', message: 'down', traceId: 't2' })
    );

    await expect(get('/health')).rejects.toMatchObject({
      status: 503,
      retryable: true,
    });
  });

  it('throws ClientError on network failure', async () => {
    globalThis.fetch = vi.fn(async () => { throw new TypeError('Failed to fetch'); });

    await expect(get('/test')).rejects.toMatchObject({
      status: 0,
      code: 'NETWORK_ERROR',
      retryable: true,
    });
  });

  it('uses credentials: include for auth paths', async () => {
    let capturedCredentials;
    globalThis.fetch = vi.fn(async (url, opts) => {
      capturedCredentials = opts.credentials;
      return jsonResponse(200, { accessToken: 'tok', expiresIn: 900 });
    });

    await post('/auth/login', {});
    expect(capturedCredentials).toBe('include');
  });

  it('uses credentials: same-origin for non-auth paths', async () => {
    let capturedCredentials;
    globalThis.fetch = vi.fn(async (url, opts) => {
      capturedCredentials = opts.credentials;
      return jsonResponse(200, {});
    });

    await get('/work-orders');
    expect(capturedCredentials).toBe('same-origin');
  });

  describe('401 single-flight refresh', () => {
    it('triggers exactly one refresh for ten concurrent 401 failures', async () => {
      let refreshCount = 0;
      globalThis.fetch = vi.fn(async (url) => {
        if (url.includes('/auth/refresh')) {
          refreshCount++;
          return jsonResponse(200, { accessToken: 'fresh-token', expiresIn: 900 });
        }
        // First call returns 401, after refresh replay returns 200
        if (tokenStore.get() === 'fresh-token') {
          return jsonResponse(200, { data: 'ok' });
        }
        return jsonResponse(401, { code: 'UNAUTHENTICATED', message: 'expired' });
      });

      const requests = Array.from({ length: 10 }, () => get('/work-orders'));
      const results = await Promise.all(requests);

      expect(refreshCount).toBe(1);
      expect(results.every(r => r?.data === 'ok')).toBe(true);
    });

    it('clears token and throws after a failed refresh', async () => {
      tokenStore.set('expired-token');
      globalThis.fetch = vi.fn(async (url) => {
        if (url.includes('/auth/refresh')) {
          return jsonResponse(401, { code: 'REAUTHENTICATION_REQUIRED', message: 'cookie expired' });
        }
        return jsonResponse(401, { code: 'UNAUTHENTICATED', message: 'expired' });
      });

      await expect(get('/work-orders')).rejects.toBeTruthy();
      expect(tokenStore.get()).toBeNull();
    });
  });
});
