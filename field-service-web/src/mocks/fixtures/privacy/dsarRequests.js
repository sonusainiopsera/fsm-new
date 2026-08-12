/**
 * Synthetic DSAR fixtures spanning all states and countdown bands.
 * No real personal data — all subject IDs are synthetic UUIDs.
 *
 * @module mocks/fixtures/privacy/dsarRequests
 */

export const dsarQueueAllStates = {
  data: [
    // At-risk: 2 days remaining, VERIFIED
    { id: 'dsar-001', requestType: 'ACCESS', subjectType: 'APP_USER', subjectId: '00000000-0000-0000-0000-000000000101',
      state: 'VERIFIED', submittedAt: '2026-07-15T08:00:00Z', dueAt: '2026-08-14T08:00:00Z',
      remainingDays: 2, atRisk: true, outcome: null, version: 2 },
    // Normal: 19 days remaining, RECEIVED
    { id: 'dsar-002', requestType: 'ERASURE', subjectType: 'APP_USER', subjectId: '00000000-0000-0000-0000-000000000102',
      state: 'RECEIVED', submittedAt: '2026-08-01T10:00:00Z', dueAt: '2026-08-31T10:00:00Z',
      remainingDays: 19, atRisk: false, outcome: null, version: 1 },
    // Fulfilled
    { id: 'dsar-003', requestType: 'RECTIFICATION', subjectType: 'APP_USER', subjectId: '00000000-0000-0000-0000-000000000103',
      state: 'FULFILLED', submittedAt: '2026-06-01T09:00:00Z', dueAt: '2026-07-01T09:00:00Z',
      remainingDays: 0, atRisk: false, outcome: 'FULFILLED', version: 3 },
    // Overdue (rejected)
    { id: 'dsar-004', requestType: 'ACCESS', subjectType: 'TECHNICIAN', subjectId: '00000000-0000-0000-0000-000000000201',
      state: 'REJECTED', submittedAt: '2026-05-01T09:00:00Z', dueAt: '2026-05-31T09:00:00Z',
      remainingDays: -5, atRisk: false, outcome: 'REJECTED', version: 2 },
    // Withdrawn
    { id: 'dsar-005', requestType: 'PORTABILITY', subjectType: 'APP_USER', subjectId: '00000000-0000-0000-0000-000000000104',
      state: 'WITHDRAWN', submittedAt: '2026-07-01T12:00:00Z', dueAt: '2026-07-31T12:00:00Z',
      remainingDays: 0, atRisk: false, outcome: 'WITHDRAWN', version: 1 },
  ],
  page: { number: 0, size: 20, totalElements: 5, totalPages: 1, estimated: false },
  _links: { self: '/api/v1/privacy/dsar-requests?page=0&size=20', next: null, prev: null },
};

export const dsarDetailVerified = {
  id: 'dsar-001', requestType: 'ACCESS', subjectType: 'APP_USER',
  subjectId: '00000000-0000-0000-0000-000000000101',
  state: 'VERIFIED', submittedAt: '2026-07-15T08:00:00Z', dueAt: '2026-08-14T08:00:00Z',
  remainingDays: 2, atRisk: true,
  identityVerifiedAt: '2026-07-16T10:00:00Z', verificationMethod: 'GOVERNMENT_ID',
  assignedHandler: null, outcome: null, version: 2, manifest: null,
};

export const dsarDetailFulfilled = {
  id: 'dsar-003', requestType: 'RECTIFICATION', subjectType: 'APP_USER',
  subjectId: '00000000-0000-0000-0000-000000000103',
  state: 'FULFILLED', submittedAt: '2026-06-01T09:00:00Z', dueAt: '2026-07-01T09:00:00Z',
  remainingDays: 0, atRisk: false,
  identityVerifiedAt: '2026-06-02T11:00:00Z', verificationMethod: 'PHONE_VERIFICATION',
  assignedHandler: null, outcome: 'FULFILLED', version: 3,
  manifest: [
    { sectionName: 'identity.app_user', sourceModule: 'identity', rowCount: 1 },
    { sectionName: 'workorder.history', sourceModule: 'workorder', rowCount: 17 },
  ],
};

export const dsarDetailUnverified = {
  id: 'dsar-002', requestType: 'ERASURE', subjectType: 'APP_USER',
  subjectId: '00000000-0000-0000-0000-000000000102',
  state: 'RECEIVED', submittedAt: '2026-08-01T10:00:00Z', dueAt: '2026-08-31T10:00:00Z',
  remainingDays: 19, atRisk: false,
  identityVerifiedAt: null, verificationMethod: null,
  assignedHandler: null, outcome: null, version: 1, manifest: null,
};

export const erasureGuardRefusal422 = {
  status: 422,
  body: {
    status: 422, code: 'GUARD_REFUSED',
    message: 'DSAR request dsar-002 must be in state VERIFIED to authorise erasure (current state: RECEIVED)',
    fieldErrors: [], traceId: 'test-trace-id',
  },
};
