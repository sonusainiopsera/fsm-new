/**
 * useJobDetail — TanStack Query hook for fetching full technician job detail.
 *
 * Fetches GET /api/v1/technicians/me/work-orders/{jobId}.
 * Invalidated on successful transition (via useWorkOrderTransition).
 *
 * @module app/technician/useJobDetail
 */

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../../api/http.js';

/**
 * @param {string} jobId
 * @returns {import('@tanstack/react-query').UseQueryResult}
 */
export function useJobDetail(jobId) {
  return useQuery({
    queryKey: ['technician', 'jobs', jobId],
    queryFn: () => apiFetch(`/technicians/me/work-orders/${jobId}`),
    enabled: !!jobId,
    staleTime: 30_000,
    retry: (failureCount, error) => {
      // Never retry 4xx errors
      if (error?.status >= 400 && error?.status < 500) return false;
      return failureCount < 2;
    },
  });
}
