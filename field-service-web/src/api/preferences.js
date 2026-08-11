const BASE = '/api/v1/users/me';

/**
 * @param {string} token  Bearer token
 * @returns {Promise<{userId: string, storedPreference: string|null, effectivePreference: string}>}
 */
export async function getPreferences(token) {
  const response = await fetch(`${BASE}/preferences`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`getPreferences failed: ${response.status}`);
  return response.json();
}

/**
 * @param {string} token      Bearer token
 * @param {string} preference One of LIGHT | DARK | SYSTEM (matches server enum)
 * @returns {Promise<{userId: string, storedPreference: string, effectivePreference: string}>}
 */
export async function updatePreference(token, preference) {
  const response = await fetch(`${BASE}/preferences`, {
    method: 'PUT',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ preference }),
  });
  if (!response.ok) throw new Error(`updatePreference failed: ${response.status}`);
  return response.json();
}
