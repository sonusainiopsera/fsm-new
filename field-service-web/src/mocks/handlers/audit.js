/**
 * @fileoverview MSW handlers for audit revision endpoints (WO-199).
 */
import { http, HttpResponse } from 'msw'
import revisionsFixture from '../fixtures/audit/audit-revisions.json'
import diffFixture      from '../fixtures/audit/audit-revision-diff.json'

const BASE = '/api/v1/admin/audit-revisions'

export const auditHandlers = [
  // Search
  http.get(BASE, ({ request }) => {
    const url = new URL(request.url)
    const entityType = url.searchParams.get('entityType')
    if (entityType && !['WorkOrder','AppUser','Site','Assignment','SlaPolicy','Customer','Asset','Technician'].includes(entityType)) {
      return HttpResponse.json(
        { code: 'INVALID_FILTER', message: 'Unknown entityType', fieldErrors: [{ field: 'entityType', message: 'Not in allow-list' }] },
        { status: 400 }
      )
    }
    return HttpResponse.json(revisionsFixture)
  }),

  // Revision diff
  http.get(`${BASE}/:revisionNumber`, () =>
    HttpResponse.json(diffFixture)
  ),

  // Export request
  http.post(`${BASE}/exports`, async ({ request }) => {
    const body = await request.json().catch(() => ({}))
    if (!['CSV','JSON'].includes(body.format)) {
      return HttpResponse.json(
        { code: 'INVALID_FORMAT', message: 'format must be CSV or JSON', fieldErrors: [{ field: 'format', message: 'Must be CSV or JSON' }] },
        { status: 400 }
      )
    }
    return HttpResponse.json({ exportId: 'ee000000-0000-0000-0000-000000000001', status: 'COMPLETED', immediate: true, content: 'rev,ts\n42,2026-06-01T10:00:00Z\n' })
  }),

  // Export status
  http.get(`${BASE}/exports/:exportId`, () =>
    HttpResponse.json({ exportId: 'ee000000-0000-0000-0000-000000000001', status: 'COMPLETED', rowCount: 1 })
  ),
]
