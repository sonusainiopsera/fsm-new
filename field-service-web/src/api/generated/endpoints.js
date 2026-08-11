// GENERATED — do not edit by hand. Run: node scripts/generate-api-client.mjs
// Source: openapi-snapshot.json

import { apiFetch } from '../http.js';

/** @param {unknown} result @param {string} opId */
function _assertShape(result, opId) {
  if (result !== null && (typeof result !== 'object' || Array.isArray(result))) {
    throw new Error(`[api] Boundary validation failed for ${opId}: unexpected shape`);
  }
}