/**
 * @fileoverview MSW-style fetch mock handlers for notification preference endpoints (WO-197).
 *
 * Endpoints:
 *   GET  /api/v1/users/{userId}/notification-preferences
 *   PUT  /api/v1/users/{userId}/notification-preferences
 *
 * Usage in tests:
 *   import { mockPreferencesHandlers } from '../../mocks/handlers/notificationPreferences.js'
 *   vi.stubGlobal('fetch', mockPreferencesHandlers.success())
 */

const BASE = '/api/v1/users'

// ── Response factories ────────────────────────────────────────────────────────

function jsonResponse(body, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (h) => {
        if (h.toLowerCase() === 'content-type') return 'application/json'
        return null
      },
    },
    json: () => Promise.resolve(body),
  })
}

/** @type {import('../../features/settings/notifications/api/useNotificationPreferences.js').PreferenceItem[]} */
const DEFAULT_PREFS = [
  { category: 'WO_ASSIGNED',   channel: 'EMAIL',  enabled: true,  source: 'EXPLICIT' },
  { category: 'WO_ASSIGNED',   channel: 'IN_APP', enabled: true,  source: 'EXPLICIT' },
  { category: 'SLA_BREACH',    channel: 'EMAIL',  enabled: false, source: 'EXPLICIT' },
  { category: 'SLA_BREACH',    channel: 'IN_APP', enabled: true,  source: 'EXPLICIT' },
]

const DEFAULT_RESPONSE = {
  data: DEFAULT_PREFS,
  page: { number: 0, size: 50, totalElements: DEFAULT_PREFS.length, totalPages: 1 },
  links: { next: null, prev: null },
}

// ── Handler factories ─────────────────────────────────────────────────────────

export const mockPreferencesHandlers = {
  /** 200 with default preferences data */
  success: (overrides = {}) => {
    return vi.fn().mockResolvedValue(
      jsonResponse({ ...DEFAULT_RESPONSE, ...overrides })
    )
  },

  /** 200 with empty data array */
  empty: () => vi.fn().mockResolvedValue(
    jsonResponse({
      data: [],
      page: { number: 0, size: 50, totalElements: 0, totalPages: 0 },
      links: { next: null, prev: null },
    })
  ),

  /** 403 forbidden */
  forbidden: () => vi.fn().mockResolvedValue(
    jsonResponse({ code: 'FORBIDDEN', message: 'Access denied.', fieldErrors: [], traceId: 'trace-001' }, 403)
  ),

  /** 400 validation error */
  validationError: (fieldErrors = []) => vi.fn().mockResolvedValue(
    jsonResponse({ code: 'VALIDATION_ERROR', message: 'Invalid input.', fieldErrors, traceId: 'trace-002' }, 400)
  ),

  /** Network error */
  networkError: () => vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),

  /**
   * Stateful handler that supports GET + PUT in the same test.
   * Returns current state on GET; updates state on PUT and returns updated response.
   */
  stateful: () => {
    let state = [...DEFAULT_PREFS]
    return vi.fn().mockImplementation((url, opts = {}) => {
      const method = (opts.method ?? 'GET').toUpperCase()
      if (method === 'PUT') {
        const body = JSON.parse(opts.body)
        const updates = body.preferences
        for (const upd of updates) {
          const idx = state.findIndex(
            (p) => p.category === upd.category && p.channel === upd.channel
          )
          if (idx >= 0) {
            state[idx] = { ...state[idx], enabled: upd.enabled, source: 'EXPLICIT' }
          } else {
            state.push({ ...upd, source: 'EXPLICIT' })
          }
        }
      }
      return jsonResponse({
        data: state,
        page: { number: 0, size: 50, totalElements: state.length, totalPages: 1 },
        links: { next: null, prev: null },
      })
    })
  },
}
