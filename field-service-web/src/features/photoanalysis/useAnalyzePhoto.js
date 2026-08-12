/**
 * TanStack Query mutation hook for photo analysis.
 *
 * Provides two mutations:
 *   - analyzePhoto: POST /api/v1/work-orders/{id}/photos/{photoId}/analysis
 *   - recordDescription: POST /api/v1/work-orders/{id}/photos/{photoId}/description
 *
 * The analyzePhoto mutation resolves to the draft DTO on success.
 * On any error, it resolves to { degraded: true, degradedCode } so the
 * calling component can show the appropriate degraded state without
 * treating a provider outage as a hard failure.
 *
 * @module features/photoanalysis/useAnalyzePhoto
 */

import { useMutation } from '@tanstack/react-query';
import { DEGRADED_CODES } from './photoAnalysisStates.js';

const API_BASE = '/api/v1/work-orders';

/**
 * Calls the analysis endpoint for a stored photo.
 *
 * @param {string} workOrderId
 * @param {string} photoId
 * @param {boolean} [includeFaultContext=false]
 * @returns {Promise<object>} draft DTO or degraded sentinel
 */
async function fetchAnalysis(workOrderId, photoId, includeFaultContext = false) {
  const res = await fetch(
    `${API_BASE}/${workOrderId}/photos/${photoId}/analysis`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ includeFaultContext }),
    }
  );

  if (res.status === 503 || res.status === 429) {
    const body = await res.json().catch(() => ({}));
    return {
      degraded: true,
      degradedCode: body.code || DEGRADED_CODES.AI_PROVIDER_UNAVAILABLE,
    };
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || `Analysis failed (${res.status})`);
  }

  return res.json();
}

/**
 * Posts the technician's final description and returns the override classification.
 *
 * @param {string} workOrderId
 * @param {string} photoId
 * @param {string|null} description
 * @param {string|null} suggestionInteractionId
 * @returns {Promise<object>} override result
 */
async function postDescription(workOrderId, photoId, description, suggestionInteractionId) {
  const res = await fetch(
    `${API_BASE}/${workOrderId}/photos/${photoId}/description`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description, suggestionInteractionId }),
    }
  );

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || `Description recording failed (${res.status})`);
  }

  return res.json();
}

/**
 * Hook that provides the analyzePhoto mutation.
 *
 * @param {object} [options] optional TanStack Query mutation options
 * @returns {{ mutate, mutateAsync, isPending, data, error }}
 */
export function useAnalyzePhoto(options = {}) {
  return useMutation({
    mutationFn: ({ workOrderId, photoId, includeFaultContext }) =>
      fetchAnalysis(workOrderId, photoId, includeFaultContext),
    ...options,
  });
}

/**
 * Hook that provides the recordDescription mutation.
 *
 * @param {object} [options] optional TanStack Query mutation options
 * @returns {{ mutate, mutateAsync, isPending, data, error }}
 */
export function useRecordDescription(options = {}) {
  return useMutation({
    mutationFn: ({ workOrderId, photoId, description, suggestionInteractionId }) =>
      postDescription(workOrderId, photoId, description, suggestionInteractionId),
    ...options,
  });
}
