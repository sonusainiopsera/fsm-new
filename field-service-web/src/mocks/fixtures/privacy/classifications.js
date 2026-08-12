/**
 * Synthetic fixtures for the classification registry.
 * No real personal data — all values are illustrative.
 *
 * @module mocks/fixtures/privacy/classifications
 */

export const classificationsPage1 = {
  data: [
    { id: 'cls-001', module: 'identity',  entityName: 'AppUser',    fieldName: 'email',       tier: 'CONFIDENTIAL', lawfulBasisNote: 'Contract performance', handlingNotes: 'Used for account communications only', updatedAt: '2026-01-01T00:00:00Z', version: 1 },
    { id: 'cls-002', module: 'identity',  entityName: 'AppUser',    fieldName: 'phoneNumber', tier: 'CONFIDENTIAL', lawfulBasisNote: null,                   handlingNotes: null,                                   updatedAt: '2026-01-01T00:00:00Z', version: 1 },
    { id: 'cls-003', module: 'identity',  entityName: 'AppUser',    fieldName: null,           tier: 'INTERNAL',     lawfulBasisNote: null,                   handlingNotes: 'Internal operational record',          updatedAt: '2026-01-01T00:00:00Z', version: 1 },
    { id: 'cls-004', module: 'workforce', entityName: 'Technician', fieldName: 'name',         tier: 'CONFIDENTIAL', lawfulBasisNote: 'Employment contract',   handlingNotes: null,                                   updatedAt: '2026-02-01T00:00:00Z', version: 2 },
    { id: 'cls-005', module: 'workforce', entityName: 'Technician', fieldName: 'passwordHash', tier: 'RESTRICTED',   lawfulBasisNote: 'Security',             handlingNotes: 'Never log or display',                 updatedAt: '2026-02-01T00:00:00Z', version: 1 },
  ],
  page: { number: 0, size: 20, totalElements: 5, totalPages: 1, estimated: false },
  _links: { self: '/api/v1/privacy/classifications?page=0&size=20', next: null, prev: null },
};

export const classificationConflict409 = {
  status: 409,
  body: { status: 409, code: 'CONFLICT', message: 'Stale version for DataClassification cls-001', fieldErrors: [], traceId: 'test-trace-id' },
};
