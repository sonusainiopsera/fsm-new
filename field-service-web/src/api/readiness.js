/**
 * @fileoverview Certification readiness API client and TanStack Query hooks.
 *
 * Endpoints:
 *   GET  /api/v1/reports/certification-readiness          → summary
 *   GET  /api/v1/reports/certification-readiness/gaps     → paginated gaps
 *   GET  /api/v1/reports/certification-readiness/gaps.csv → CSV export
 *   GET  /api/v1/reports/certification-readiness/snapshots → trend
 *   POST /api/v1/reports/certification-readiness/snapshots → generate snapshot
 *   GET  /api/v1/reports/certification-readiness/requirements → list requirements
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { request } from './http.js'

const BASE = '/reports/certification-readiness'

// ── Query key factories ───────────────────────────────────────────────────────

export const readinessKeys = {
  summary:      () => ['readiness', 'summary'],
  gaps:         (params) => ['readiness', 'gaps', params],
  snapshots:    (params) => ['readiness', 'snapshots', params],
  requirements: () => ['readiness', 'requirements'],
}

// ── API functions ─────────────────────────────────────────────────────────────

export async function fetchReadinessSummary() {
  return request(BASE)
}

export async function fetchReadinessGaps({ page = 0, size = 25 } = {}) {
  return request(`${BASE}/gaps?page=${page}&size=${size}`)
}

export async function fetchReadinessSnapshots({ page = 0, size = 25 } = {}) {
  return request(`${BASE}/snapshots?page=${page}&size=${size}`)
}

export async function generateReadinessSnapshot() {
  return request(`${BASE}/snapshots`, { method: 'POST' })
}

export async function fetchReadinessRequirements() {
  return request(`${BASE}/requirements`)
}

// ── TanStack Query hooks ──────────────────────────────────────────────────────

export function useReadinessSummary() {
  return useQuery({
    queryKey: readinessKeys.summary(),
    queryFn:  fetchReadinessSummary,
  })
}

export function useReadinessGaps({ page = 0, size = 25 } = {}) {
  return useQuery({
    queryKey: readinessKeys.gaps({ page, size }),
    queryFn:  () => fetchReadinessGaps({ page, size }),
    keepPreviousData: true,
  })
}

export function useReadinessSnapshots({ page = 0, size = 25 } = {}) {
  return useQuery({
    queryKey: readinessKeys.snapshots({ page, size }),
    queryFn:  () => fetchReadinessSnapshots({ page, size }),
    keepPreviousData: true,
  })
}

export function useReadinessRequirements() {
  return useQuery({
    queryKey: readinessKeys.requirements(),
    queryFn:  fetchReadinessRequirements,
  })
}

export function useGenerateSnapshot() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: generateReadinessSnapshot,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: readinessKeys.snapshots({}) })
      queryClient.invalidateQueries({ queryKey: readinessKeys.summary() })
    },
  })
}
