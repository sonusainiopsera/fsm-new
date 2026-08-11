/**
 * @fileoverview API client for GET/PUT /api/v1/users/me/preferences.
 *
 * Follows the project transport convention: returns { data, error } from
 * fetch responses.  The caller (AppearanceProvider / TanStack Query mutation)
 * is responsible for optimistic state and rollback on error.
 */

const PREFERENCES_URL = '/api/v1/users/me/preferences'

/**
 * @typedef {{ storedPreference: string | null, effectiveAppearance: string, userId: string }} PreferencesData
 * @typedef {{ code: string, message: string }} ApiError
 */

/**
 * Fetches the current user's preferences.
 *
 * @param {string} accessToken  Bearer token for the authenticated session.
 * @returns {Promise<{ data: PreferencesData | null, error: ApiError | null }>}
 */
export async function fetchPreferences(accessToken) {
  try {
    const res = await fetch(PREFERENCES_URL, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      return { data: null, error: { code: err.code ?? 'FETCH_ERROR', message: err.message ?? res.statusText } }
    }
    return { data: await res.json(), error: null }
  } catch (e) {
    return { data: null, error: { code: 'NETWORK_ERROR', message: String(e) } }
  }
}

/**
 * Updates the current user's appearance preference.
 *
 * @param {string} accessToken  Bearer token for the authenticated session.
 * @param {'LIGHT' | 'DARK' | 'SYSTEM'} appearance
 * @param {string} [idempotencyKey]  Optional idempotency key (UUID) for safe retries.
 * @returns {Promise<{ data: PreferencesData | null, error: ApiError | null }>}
 */
export async function updatePreference(accessToken, appearance, idempotencyKey) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${accessToken}`,
  }
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey
  }
  try {
    const res = await fetch(PREFERENCES_URL, {
      method: 'PUT',
      headers,
      body: JSON.stringify({ appearance }),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      return { data: null, error: { code: err.code ?? 'UPDATE_ERROR', message: err.message ?? res.statusText } }
    }
    return { data: await res.json(), error: null }
  } catch (e) {
    return { data: null, error: { code: 'NETWORK_ERROR', message: String(e) } }
  }
}
