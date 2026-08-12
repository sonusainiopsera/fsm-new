/**
 * @typedef {{ category: string, channel: string, enabled: boolean, source: 'DEFAULT' | 'EXPLICIT' }} NotificationPreferenceDto
 * @typedef {{
 *   data: NotificationPreferenceDto[],
 *   page: { number: number, size: number, totalElements: number, totalPages: number },
 *   links: { next: string|null, prev: string|null }
 * }} NotificationPreferencesResponse
 */

/**
 * @typedef {{ message: string, status?: number, code?: string, traceId?: string }} ApiError
 */

const BASE_URL = '/api/v1'

/**
 * Validates that a value matches the NotificationPreferenceDto shape.
 * Throws an informative error if validation fails.
 * @param {unknown} item
 * @param {number} index
 * @returns {asserts item is NotificationPreferenceDto}
 */
function assertPreferenceDto(item, index) {
  if (typeof item !== 'object' || item === null) {
    throw new Error(
      `API response validation failed: data[${index}] is not an object, got ${typeof item}`
    )
  }
  const obj = /** @type {Record<string, unknown>} */ (item)
  if (typeof obj['category'] !== 'string') {
    throw new Error(
      `API response validation failed: data[${index}].category must be a string, got ${typeof obj['category']}`
    )
  }
  if (typeof obj['channel'] !== 'string') {
    throw new Error(
      `API response validation failed: data[${index}].channel must be a string, got ${typeof obj['channel']}`
    )
  }
  if (typeof obj['enabled'] !== 'boolean') {
    throw new Error(
      `API response validation failed: data[${index}].enabled must be a boolean, got ${typeof obj['enabled']}`
    )
  }
  if (obj['source'] !== 'DEFAULT' && obj['source'] !== 'EXPLICIT') {
    throw new Error(
      `API response validation failed: data[${index}].source must be 'DEFAULT' or 'EXPLICIT', got ${String(obj['source'])}`
    )
  }
}

/**
 * Validates the full response shape.
 * @param {unknown} body
 * @returns {asserts body is NotificationPreferencesResponse}
 */
function assertPreferencesResponse(body) {
  if (typeof body !== 'object' || body === null) {
    throw new Error(
      `API response validation failed: expected an object, got ${typeof body}`
    )
  }
  const obj = /** @type {Record<string, unknown>} */ (body)
  if (!Array.isArray(obj['data'])) {
    throw new Error(
      `API response validation failed: expected data to be an array, got ${typeof obj['data']}`
    )
  }
  obj['data'].forEach((item, index) => assertPreferenceDto(item, index))

  if (typeof obj['page'] !== 'object' || obj['page'] === null) {
    throw new Error(`API response validation failed: expected page to be an object`)
  }
  const page = /** @type {Record<string, unknown>} */ (obj['page'])
  for (const field of ['number', 'size', 'totalElements', 'totalPages']) {
    if (typeof page[field] !== 'number') {
      throw new Error(
        `API response validation failed: page.${field} must be a number, got ${typeof page[field]}`
      )
    }
  }

  if (typeof obj['links'] !== 'object' || obj['links'] === null) {
    throw new Error(`API response validation failed: expected links to be an object`)
  }
}

/**
 * Creates an API error with attached metadata.
 * @param {string} message
 * @param {{ status?: number, code?: string, traceId?: string }} meta
 * @returns {Error & ApiError}
 */
function makeApiError(message, meta) {
  const err = new Error(message)
  return Object.assign(err, meta)
}

/**
 * @param {string} userId
 * @param {{ page?: number, size?: number }} [params]
 * @returns {Promise<NotificationPreferencesResponse>}
 */
export async function getNotificationPreferences(userId, params = {}) {
  const url = new URL(
    `${BASE_URL}/users/${encodeURIComponent(userId)}/notification-preferences`,
    window.location.origin
  )
  if (params.page !== undefined) url.searchParams.set('page', String(params.page))
  if (params.size !== undefined) url.searchParams.set('size', String(params.size))

  const response = await fetch(url.toString(), {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
  })

  if (response.status === 403 || response.status === 401) {
    throw makeApiError(
      'You do not have permission to view notification preferences.',
      { status: response.status, code: 'PERMISSION_DENIED' }
    )
  }

  if (!response.ok) {
    const traceId = response.headers.get('X-Trace-Id') ?? undefined
    throw makeApiError(
      `Failed to load notification preferences (HTTP ${response.status})`,
      { status: response.status, traceId }
    )
  }

  const body = await response.json()
  assertPreferencesResponse(body)
  return body
}

/**
 * @param {string} userId
 * @param {{ preferences: Array<{ category: string, channel: string, enabled: boolean }> }} body
 * @returns {Promise<NotificationPreferencesResponse>}
 */
export async function upsertNotificationPreferences(userId, body) {
  const url = `${BASE_URL}/users/${encodeURIComponent(userId)}/notification-preferences`

  const response = await fetch(url, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  })

  if (response.status === 403 || response.status === 401) {
    throw makeApiError(
      'You do not have permission to update notification preferences.',
      { status: response.status, code: 'PERMISSION_DENIED' }
    )
  }

  if (!response.ok) {
    const traceId = response.headers.get('X-Trace-Id') ?? undefined
    throw makeApiError(
      `Failed to save notification preferences (HTTP ${response.status})`,
      { status: response.status, traceId }
    )
  }

  const responseBody = await response.json()
  assertPreferencesResponse(responseBody)
  return responseBody
}
