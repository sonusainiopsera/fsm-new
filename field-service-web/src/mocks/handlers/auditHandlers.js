/**
 * MSW-style mock handlers and fixtures for the audit revision search surface.
 *
 * Provides fixtures for:
 * - Audit revision search (paginated)
 * - Revision detail with field diff
 * - Synchronous CSV/JSON export
 * - Asynchronous export handle and status polling
 * - 403 permission denied
 * - 400 invalid entityType
 *
 * @module auditHandlers
 */

const REVISION_BASE = '/api/v1/admin/audit-revisions';
const EXPORT_BASE   = '/api/v1/admin/audit-exports';

// ── Fixtures ─────────────────────────────────────────────────────────────────────────────

/**
 * @typedef {Object} RevisionSummary
 * @property {number} revisionNumber
 * @property {string} revisionTimestamp
 * @property {string} actorUserId
 * @property {string} actorRole
 * @property {string} entityType
 * @property {string} entityId
 * @property {string} changeType
 * @property {string[]} changedFieldNames
 */

/**
 * Returns a page of audit revision summaries.
 *
 * @param {{ size?: number, nextCursor?: string|null, hasMore?: boolean } = {}} opts
 * @returns {{ status: 200, body: { data: RevisionSummary[], nextCursor: string|null, hasMore: boolean } }}
 */
export function revisionPageFixture({ size = 5, nextCursor = null, hasMore = false } = {}) {
  /** @type {RevisionSummary[]} */
  const data = Array.from({ length: size }, (_, i) => ({
    revisionNumber:    10001 + i,
    revisionTimestamp: new Date(Date.now() - i * 3600 * 1000).toISOString(),
    actorUserId:       'dddddddd-0000-0000-0000-000000000011',
    actorRole:         'ADMIN',
    entityType:        'WorkOrder',
    entityId:          '00000000-0000-7199-8000-000000000001',
    changeType:        i === 0 ? 'ADD' : 'MOD',
    changedFieldNames: i === 0 ? ['state', 'priority'] : ['state'],
  }));

  return {
    status: 200,
    body: { data, nextCursor, hasMore },
  };
}

/**
 * Returns an empty revision page.
 *
 * @returns {{ status: 200, body: { data: [], nextCursor: null, hasMore: false } }}
 */
export function emptyRevisionPageFixture() {
  return {
    status: 200,
    body: { data: [], nextCursor: null, hasMore: false },
  };
}

/**
 * @typedef {Object} FieldDiff
 * @property {string} name
 * @property {string|null} before
 * @property {string|null} after
 * @property {boolean} changed
 * @property {boolean} masked
 */

/**
 * Returns a revision detail with field-level diff.
 *
 * @param {{ revisionNumber?: number } = {}} opts
 * @returns {{ status: 200, body: { data: object } }}
 */
export function revisionDetailFixture({ revisionNumber = 10001 } = {}) {
  /** @type {FieldDiff[]} */
  const fields = [
    { name: 'state',    before: null,   after: 'NEW',  changed: true,  masked: false },
    { name: 'priority', before: null,   after: 'HIGH', changed: true,  masked: false },
    { name: 'description', before: null, after: 'Boiler fault.', changed: true, masked: false },
  ];

  return {
    status: 200,
    body: {
      data: {
        revisionNumber,
        revisionTimestamp: new Date(Date.now() - 7200000).toISOString(),
        actorUserId: 'dddddddd-0000-0000-0000-000000000011',
        actorRole:   'ADMIN',
        entityType:  'WorkOrder',
        entityId:    '00000000-0000-7199-8000-000000000001',
        fields,
      },
    },
  };
}

/**
 * Returns a 403 permission-denied response.
 *
 * @returns {{ status: 403, body: { code: string, message: string } }}
 */
export function forbiddenFixture() {
  return {
    status: 403,
    body: {
      code:        'FORBIDDEN',
      message:     'Access denied.',
      fieldErrors: [],
      traceId:     'test-trace-id',
    },
  };
}

/**
 * Returns a 400 invalid entityType response.
 *
 * @returns {{ status: 400, body: object }}
 */
export function invalidEntityTypeFixture() {
  return {
    status: 400,
    body: {
      code:    'VALIDATION_FAILED',
      message: 'Invalid audit filter.',
      fieldErrors: [{ field: 'entityType', rejectedValue: 'DROP TABLE', message: 'Value is not in the allow-listed set for entityType' }],
      traceId: 'test-trace-id',
    },
  };
}

/**
 * Returns a 202 async export handle response.
 *
 * @param {{ exportId?: string } = {}} opts
 * @returns {{ status: 202, body: { exportId: string, status: string } }}
 */
export function asyncExportHandleFixture({ exportId = '00000000-0000-7199-9000-000000000001' } = {}) {
  return {
    status: 202,
    body: { exportId, status: 'QUEUED' },
  };
}

/**
 * Returns a completed export status response.
 *
 * @param {{ exportId?: string } = {}} opts
 * @returns {{ status: 200, body: { data: object } }}
 */
export function exportStatusCompletedFixture({ exportId = '00000000-0000-7199-9000-000000000001' } = {}) {
  return {
    status: 200,
    body: {
      data: {
        exportId,
        status:            'COMPLETED',
        rowCount:          42,
        downloadReference: 'audit-export-token-abc123',
        failureReason:     null,
      },
    },
  };
}
