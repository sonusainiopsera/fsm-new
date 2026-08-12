/**
 * Copilot mock handlers — extends the main handler table with copilot-specific routes.
 *
 * Routes added:
 *   POST /api/v1/copilot/stream-ticket  → { ticket, streamUrl }
 *   POST /api/v1/copilot/interactions/:id/rating → 204
 *
 * SSE streams are simulated via MockSseSession (not fetch) in tests;
 * this module provides the ticket and rating HTTP endpoints only.
 *
 * Usage in tests:
 *   import { installHandlers, resetHandlers } from '../handlers/index.js';
 *   import { addCopilotHandlers } from '../handlers/copilotHandlers.js';
 *
 *   beforeAll(() => { installHandlers(); addCopilotHandlers(); });
 *   afterEach(() => resetHandlers());
 */

import { mockRespond } from './index.js';

export const MOCK_COPILOT_TICKET = 'copilot-stream-ticket-test-001';
export const MOCK_STREAM_URL     = '/api/v1/copilot/stream';

/**
 * Registers default copilot route overrides for a test run.
 * Call after installHandlers().
 */
export function addCopilotHandlers() {
  mockRespond('POST', '/api/v1/copilot/stream-ticket', {
    status: 200,
    body: {
      ticket:    MOCK_COPILOT_TICKET,
      streamUrl: MOCK_STREAM_URL,
      expiresIn: 60,
    },
  });
  // Pre-register the rating endpoint (any interactionId); per-test overrides can win.
  mockRespond('POST', '/api/v1/copilot/interactions/interaction-001/rating', {
    status: 204,
    body: null,
  });
  // Individual job detail with copilotEnabled flag
  mockRespond('GET', '/api/v1/technicians/me/work-orders/wo-001', {
    status: 200,
    body: {
      id: 'wo-001', reference: 'WO-0042', state: 'IN_PROGRESS', priority: 'HIGH',
      customerName: 'Acme Corp', siteName: 'Main Campus', siteAddress: '1 Main St, London',
      description: 'Compressor failure — plant room B',
      assetId: 'asset-001',
      scheduledAt: '2026-08-12T09:00:00Z',
      responseDeadline: '2026-08-12T13:00:00Z', resolutionDeadline: '2026-08-12T17:00:00Z',
      allowedTransitions: ['COMPLETE', 'ON_HOLD'],
      holdReasons: [{ code: 'PARTS', label: 'Awaiting parts', sortOrder: 1 }],
      atRisk: false, version: 2,
      copilotEnabled: true,
    },
  });
}

/**
 * Returns a 429 RATE_LIMITED fixture for the stream-ticket endpoint.
 * @param {number} [retryAfterSeconds]
 */
export function copilotCappedFixture(retryAfterSeconds = 300) {
  return {
    status: 429,
    body: {
      status: 429,
      code: 'RATE_LIMITED',
      message: 'Daily copilot query limit reached.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
    headers: { 'Retry-After': String(retryAfterSeconds) },
  };
}

/**
 * Returns a 503 SERVICE_UNAVAILABLE fixture for the stream-ticket endpoint.
 */
export function copilotUnavailableFixture() {
  return {
    status: 503,
    body: {
      status: 503,
      code: 'SERVICE_UNAVAILABLE',
      message: 'Copilot service temporarily unavailable.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
  };
}

/**
 * Returns a 500 error fixture for the rating endpoint.
 */
export function ratingFailureFixture() {
  return {
    status: 500,
    body: {
      status: 500,
      code: 'UNEXPECTED_ERROR',
      message: 'Rating could not be recorded.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
  };
}
