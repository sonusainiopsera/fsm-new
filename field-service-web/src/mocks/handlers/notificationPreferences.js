import { http, HttpResponse } from 'msw'

/** @type {import('../../api/notificationPreferences.js').NotificationPreferenceDto[]} */
const defaultPreferences = [
  // WORK_ORDER_CREATED
  { category: 'WORK_ORDER_CREATED', channel: 'EMAIL', enabled: true, source: 'DEFAULT' },
  { category: 'WORK_ORDER_CREATED', channel: 'SMS', enabled: false, source: 'DEFAULT' },
  { category: 'WORK_ORDER_CREATED', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
  { category: 'WORK_ORDER_CREATED', channel: 'PUSH', enabled: true, source: 'DEFAULT' },
  // WORK_ORDER_ASSIGNED
  { category: 'WORK_ORDER_ASSIGNED', channel: 'EMAIL', enabled: true, source: 'DEFAULT' },
  { category: 'WORK_ORDER_ASSIGNED', channel: 'SMS', enabled: true, source: 'DEFAULT' },
  { category: 'WORK_ORDER_ASSIGNED', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
  { category: 'WORK_ORDER_ASSIGNED', channel: 'PUSH', enabled: true, source: 'DEFAULT' },
  // WORK_ORDER_UPDATED
  { category: 'WORK_ORDER_UPDATED', channel: 'EMAIL', enabled: false, source: 'DEFAULT' },
  { category: 'WORK_ORDER_UPDATED', channel: 'SMS', enabled: false, source: 'DEFAULT' },
  { category: 'WORK_ORDER_UPDATED', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
  { category: 'WORK_ORDER_UPDATED', channel: 'PUSH', enabled: false, source: 'DEFAULT' },
  // WORK_ORDER_COMPLETED
  { category: 'WORK_ORDER_COMPLETED', channel: 'EMAIL', enabled: true, source: 'EXPLICIT' },
  { category: 'WORK_ORDER_COMPLETED', channel: 'SMS', enabled: false, source: 'DEFAULT' },
  { category: 'WORK_ORDER_COMPLETED', channel: 'IN_APP', enabled: true, source: 'EXPLICIT' },
  { category: 'WORK_ORDER_COMPLETED', channel: 'PUSH', enabled: false, source: 'DEFAULT' },
  // SLA_AT_RISK
  { category: 'SLA_AT_RISK', channel: 'EMAIL', enabled: true, source: 'EXPLICIT' },
  { category: 'SLA_AT_RISK', channel: 'SMS', enabled: true, source: 'EXPLICIT' },
  { category: 'SLA_AT_RISK', channel: 'IN_APP', enabled: true, source: 'EXPLICIT' },
  { category: 'SLA_AT_RISK', channel: 'PUSH', enabled: true, source: 'EXPLICIT' },
  // SLA_BREACH
  { category: 'SLA_BREACH', channel: 'EMAIL', enabled: true, source: 'EXPLICIT' },
  { category: 'SLA_BREACH', channel: 'SMS', enabled: true, source: 'EXPLICIT' },
  { category: 'SLA_BREACH', channel: 'IN_APP', enabled: true, source: 'EXPLICIT' },
  { category: 'SLA_BREACH', channel: 'PUSH', enabled: true, source: 'EXPLICIT' },
  // PARTS_REQUEST
  { category: 'PARTS_REQUEST', channel: 'EMAIL', enabled: false, source: 'DEFAULT' },
  { category: 'PARTS_REQUEST', channel: 'SMS', enabled: false, source: 'DEFAULT' },
  { category: 'PARTS_REQUEST', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
  { category: 'PARTS_REQUEST', channel: 'PUSH', enabled: false, source: 'DEFAULT' },
  // TECHNICIAN_EN_ROUTE
  { category: 'TECHNICIAN_EN_ROUTE', channel: 'EMAIL', enabled: false, source: 'DEFAULT' },
  { category: 'TECHNICIAN_EN_ROUTE', channel: 'SMS', enabled: true, source: 'EXPLICIT' },
  { category: 'TECHNICIAN_EN_ROUTE', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
  { category: 'TECHNICIAN_EN_ROUTE', channel: 'PUSH', enabled: true, source: 'EXPLICIT' },
]

/**
 * In-memory store for the mock server — allows mutations to persist within a session.
 * @type {import('../../api/notificationPreferences.js').NotificationPreferenceDto[]}
 */
let store = defaultPreferences.map((p) => ({ ...p }))

/**
 * Resets the mock store to defaults. Useful in tests.
 */
export function resetNotificationPreferencesStore() {
  store = defaultPreferences.map((p) => ({ ...p }))
}

/**
 * Returns a paged response from the current store.
 * @param {number} page
 * @param {number} size
 * @returns {import('../../api/notificationPreferences.js').NotificationPreferencesResponse}
 */
function buildResponse(page, size) {
  const totalElements = store.length
  const totalPages = Math.ceil(totalElements / size)
  const start = page * size
  const pageData = store.slice(start, start + size)
  return {
    data: pageData,
    page: { number: page, size, totalElements, totalPages },
    links: {
      next: page + 1 < totalPages ? `/api/v1/users/mock-user/notification-preferences?page=${page + 1}&size=${size}` : null,
      prev: page > 0 ? `/api/v1/users/mock-user/notification-preferences?page=${page - 1}&size=${size}` : null,
    },
  }
}

export const notificationPreferencesHandlers = [
  // GET /api/v1/users/:userId/notification-preferences
  http.get('/api/v1/users/:userId/notification-preferences', ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '100')
    return HttpResponse.json(buildResponse(page, size))
  }),

  // PUT /api/v1/users/:userId/notification-preferences
  http.put('/api/v1/users/:userId/notification-preferences', async ({ request }) => {
    const rawBody = await request.json()

    if (!rawBody || typeof rawBody !== 'object') {
      return HttpResponse.json({ error: 'Invalid request body' }, { status: 400 })
    }

    /** @type {{ preferences: Array<{ category: string, channel: string, enabled: boolean }> }} */
    const body = /** @type {any} */ (rawBody)

    if (!Array.isArray(body.preferences)) {
      return HttpResponse.json({ error: 'Invalid request body' }, { status: 400 })
    }

    // Apply updates to store
    const patchMap = new Map(
      body.preferences.map((p) => [`${p.category}:${p.channel}`, p.enabled])
    )
    store = store.map((item) => {
      const key = `${item.category}:${item.channel}`
      const patched = patchMap.get(key)
      if (patched !== undefined) {
        return { ...item, enabled: patched, source: /** @type {'EXPLICIT'} */ ('EXPLICIT') }
      }
      return item
    })

    return HttpResponse.json(buildResponse(0, 100))
  }),
]
