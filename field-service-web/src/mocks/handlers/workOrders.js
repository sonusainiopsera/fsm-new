/**
 * MSW-style mock handlers and fixtures for the work-order board.
 *
 * Provides fixtures for:
 * - Board search response (all states and priorities)
 * - Empty result
 * - 403 permission denied
 * - Single work-order detail
 * - Transition success
 * - Each transition refusal code (409 ILLEGAL, 409 VERSION_CONFLICT, 422 GUARD_REFUSED, 429 RATE_LIMITED)
 *
 * Usage with the central handler registry:
 *   import { mockRespond, errorFixture } from './index.js';
 *   import { boardFixture, transitionIllegalFixture } from './workOrders.js';
 *   mockRespond('GET', '/api/v1/work-orders', boardFixture());
 */

// ---- Board response fixtures -------------------------------------------

/**
 * Returns a paginated board response with work orders across all states.
 *
 * @param {{ page?: number, size?: number, totalElements?: number } = {}} opts
 * @returns {{ status: 200, body: unknown, headers: Record<string, string> }}
 */
export function boardFixture({ page = 0, size = 25, totalElements = 7 } = {}) {
  return {
    status: 200,
    body: {
      data: [
        {
          id: 'wo-001', reference: 'WO-2026-001', state: 'NEW', priority: 'HIGH',
          customerName: 'Acme Corp', siteName: 'London HQ',
          assignedTechnicianName: null,
          responseDeadline: '2026-08-13T09:00:00Z', resolutionDeadline: '2026-08-14T17:00:00Z',
          atRisk: false, legalNextEvents: ['ASSIGN', 'CANCEL'],
          version: 1, createdAt: '2026-08-12T08:00:00Z',
          description: 'HVAC unit fault reported by site manager.',
          faultCode: 'HV-ERR-42', faultCategory: 'MECHANICAL',
        },
        {
          id: 'wo-002', reference: 'WO-2026-002', state: 'ASSIGNED', priority: 'NORMAL',
          customerName: 'Beta Industries', siteName: 'Site B1',
          assignedTechnicianName: 'J. Smith',
          responseDeadline: '2026-08-13T12:00:00Z', resolutionDeadline: '2026-08-15T17:00:00Z',
          atRisk: false, legalNextEvents: ['DEPART', 'CANCEL'],
          version: 2, createdAt: '2026-08-11T14:00:00Z',
          description: null, faultCode: null, faultCategory: null,
        },
        {
          id: 'wo-003', reference: 'WO-2026-003', state: 'EN_ROUTE', priority: 'URGENT',
          customerName: 'Gamma Ltd', siteName: 'Factory Floor',
          assignedTechnicianName: 'J. Doe',
          responseDeadline: '2026-08-12T11:00:00Z', resolutionDeadline: '2026-08-12T17:00:00Z',
          atRisk: true, legalNextEvents: ['START', 'CANCEL'],
          version: 3, createdAt: '2026-08-12T09:00:00Z',
          description: 'Critical pump failure.',
          faultCode: 'PMP-FAIL', faultCategory: 'HYDRAULIC',
        },
        {
          id: 'wo-004', reference: 'WO-2026-004', state: 'IN_PROGRESS', priority: 'HIGH',
          customerName: 'Delta Co', siteName: 'Office Park',
          assignedTechnicianName: 'A. Jones',
          responseDeadline: '2026-08-12T10:00:00Z', resolutionDeadline: '2026-08-13T12:00:00Z',
          atRisk: true, legalNextEvents: ['HOLD', 'COMPLETE', 'CANCEL'],
          version: 4, createdAt: '2026-08-11T10:00:00Z',
          description: null, faultCode: null, faultCategory: null,
        },
        {
          id: 'wo-005', reference: 'WO-2026-005', state: 'ON_HOLD', priority: 'NORMAL',
          customerName: 'Epsilon LLC', siteName: 'Warehouse',
          assignedTechnicianName: 'B. Clark',
          responseDeadline: null, resolutionDeadline: '2026-08-16T17:00:00Z',
          atRisk: false, legalNextEvents: ['RESUME', 'CANCEL'],
          version: 5, createdAt: '2026-08-10T09:00:00Z',
          description: 'Awaiting spare part delivery.',
          faultCode: 'EL-SHORT', faultCategory: 'ELECTRICAL',
        },
        {
          id: 'wo-006', reference: 'WO-2026-006', state: 'COMPLETED', priority: 'LOW',
          customerName: 'Zeta Corp', siteName: 'Head Office',
          assignedTechnicianName: 'C. Brown',
          responseDeadline: null, resolutionDeadline: null,
          atRisk: false, legalNextEvents: ['CLOSE'],
          version: 6, createdAt: '2026-08-09T08:00:00Z',
          description: null, faultCode: null, faultCategory: null,
        },
        {
          id: 'wo-007', reference: 'WO-2026-007', state: 'CLOSED', priority: 'NORMAL',
          customerName: 'Eta Inc', siteName: 'Depot',
          assignedTechnicianName: 'D. White',
          responseDeadline: null, resolutionDeadline: null,
          atRisk: false, legalNextEvents: [],
          version: 7, createdAt: '2026-08-08T08:00:00Z',
          description: null, faultCode: null, faultCategory: null,
        },
      ],
      page: { number: page, size, totalElements, totalPages: Math.ceil(totalElements / size), estimated: false },
      _links: {
        self:  `/api/v1/work-orders?page=${page}&size=${size}`,
        next:  page * size + size < totalElements ? `/api/v1/work-orders?page=${page + 1}&size=${size}` : null,
        prev:  page > 0 ? `/api/v1/work-orders?page=${page - 1}&size=${size}` : null,
      },
    },
    headers: { ETag: '"board-etag-v1"' },
  };
}

/**
 * Empty board response (no work orders match the current filters).
 *
 * @returns {{ status: 200, body: unknown }}
 */
export function boardEmptyFixture() {
  return {
    status: 200,
    body: {
      data: [],
      page: { number: 0, size: 25, totalElements: 0, totalPages: 0, estimated: false },
      _links: { self: '/api/v1/work-orders?page=0&size=25', next: null, prev: null },
    },
    headers: { ETag: '"board-empty-etag"' },
  };
}

/**
 * Single work-order detail fixture.
 *
 * @param {string} [id]
 * @param {Partial<object>} [overrides]
 * @returns {{ status: 200, body: unknown }}
 */
export function workOrderDetailFixture(id = 'wo-001', overrides = {}) {
  return {
    status: 200,
    body: {
      id,
      reference: 'WO-2026-001',
      state: 'NEW',
      priority: 'HIGH',
      customerName: 'Acme Corp',
      siteName: 'London HQ',
      assignedTechnicianName: null,
      responseDeadline: '2026-08-13T09:00:00Z',
      resolutionDeadline: '2026-08-14T17:00:00Z',
      atRisk: false,
      legalNextEvents: ['ASSIGN', 'CANCEL'],
      version: 1,
      createdAt: '2026-08-12T08:00:00Z',
      description: 'HVAC unit fault reported by site manager.',
      faultCode: 'HV-ERR-42',
      faultCategory: 'MECHANICAL',
      ...overrides,
    },
  };
}

// ---- Transition fixtures -----------------------------------------------

/**
 * Successful transition response.
 * @param {string} [newState]
 * @returns {{ status: 200, body: unknown }}
 */
export function transitionSuccessFixture(newState = 'ASSIGNED') {
  return {
    status: 200,
    body: {
      id: 'wo-001',
      state: newState,
      version: 2,
      legalNextEvents: ['DEPART', 'CANCEL'],
    },
  };
}

/**
 * 409 illegal transition — the requested event is not legal from the current state.
 * Simulates another dispatcher having already acted on this work order.
 *
 * @param {string[]} [legalNextEvents]
 * @returns {{ status: 409, body: unknown }}
 */
export function transitionIllegalFixture(legalNextEvents = ['DEPART', 'CANCEL']) {
  return {
    status: 409,
    body: {
      status: 409,
      code: 'WORK_ORDER_ILLEGAL_TRANSITION',
      message: 'The requested transition is not legal from the current state.',
      fieldErrors: [
        {
          field: 'legalNextEvents',
          message: legalNextEvents.join(','),
        },
      ],
      traceId: 'test-trace-illegal',
    },
  };
}

/**
 * 409 version conflict — another actor updated the work order since it was last fetched.
 *
 * @returns {{ status: 409, body: unknown }}
 */
export function transitionVersionConflictFixture() {
  return {
    status: 409,
    body: {
      status: 409,
      code: 'WORK_ORDER_VERSION_CONFLICT',
      message: 'The work order was modified by another user. Please reload and retry.',
      fieldErrors: [],
      traceId: 'test-trace-version',
    },
  };
}

/**
 * 422 guard refusal — a named guard condition was not met.
 *
 * @param {string} [guardMessage]
 * @returns {{ status: 422, body: unknown }}
 */
export function transitionGuardRefusedFixture(guardMessage = 'Technician must hold a valid GAS_SAFE certification.') {
  return {
    status: 422,
    body: {
      status: 422,
      code: 'WORK_ORDER_GUARD_REFUSED',
      message: 'A required condition was not met.',
      fieldErrors: [
        { field: 'guard', message: guardMessage },
      ],
      traceId: 'test-trace-guard',
    },
  };
}

/**
 * 429 rate-limited transition.
 *
 * @param {number} [retryAfterSeconds]
 * @returns {{ status: 429, body: unknown, headers: Record<string, string> }}
 */
export function transitionRateLimitedFixture(retryAfterSeconds = 10) {
  return {
    status: 429,
    body: {
      status: 429,
      code: 'RATE_LIMITED',
      message: 'Too many transition requests. Please wait before retrying.',
      fieldErrors: [],
      traceId: 'test-trace-rate',
    },
    headers: { 'Retry-After': String(retryAfterSeconds) },
  };
}

/**
 * 403 permission denied for the board.
 * @returns {{ status: 403, body: unknown }}
 */
export function boardForbiddenFixture() {
  return {
    status: 403,
    body: {
      status: 403,
      code: 'FORBIDDEN',
      message: 'You do not have permission to access the work order board.',
      fieldErrors: [],
      traceId: 'test-trace-403',
    },
  };
}
