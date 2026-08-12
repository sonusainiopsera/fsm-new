/**
 * @fileoverview TanStack Query hook for the dispatch recommendations endpoint.
 *
 * Uses useInfiniteQuery with keyset cursor pagination so loading more rows
 * appends without duplicating what is already shown.
 *
 * Data contract (from the server):
 *   { data: CandidateDto[], page: { size, hasNext }, links: { next }, meta }
 *
 * The client NEVER re-ranks or re-scores candidates. All ordering, scores and
 * explanations are rendered verbatim from the server response.
 *
 * @module features/dispatch/api/useRecommendations
 */
import { useInfiniteQuery } from '@tanstack/react-query'
import { get } from '../../../api/http.js'

/** Stale time for the recommendation list — 60 s gives dispatchers a working
 *  session without spurious refetches on tab focus. */
const RECOMMENDATIONS_STALE_TIME = 60_000

/**
 * @typedef {{
 *   factorCode: string,
 *   rawValue: number,
 *   normalisedValue: number,
 *   weight: number,
 *   weightedContribution: number,
 *   explanation: string,
 *   degraded: boolean
 * }} FactorDto
 */

/**
 * @typedef {{
 *   partId: string,
 *   partNumber: string,
 *   requested: number,
 *   onHand: number,
 *   shortfall: number
 * }} PartsShortfallEntry
 */

/**
 * @typedef {{
 *   status: string,
 *   satisfactionRatio: number,
 *   shortfalls: PartsShortfallEntry[]
 * }} PartsAvailabilitySummary
 */

/**
 * @typedef {{
 *   technicianId: string,
 *   technicianName: string,
 *   rank: number,
 *   score: number,
 *   factors: FactorDto[],
 *   travelEstimateDegraded: boolean,
 *   partsAvailability: PartsAvailabilitySummary | null
 * }} CandidateDto
 */

/**
 * @typedef {{
 *   reason: string,
 *   count: number
 * }} ExclusionSummaryEntry
 */

/**
 * @typedef {{
 *   code: string,
 *   shortfalls: PartsShortfallEntry[]
 * }} PartsWarning
 */

/**
 * @typedef {{
 *   snapshotId: string,
 *   generatedAt: string,
 *   weightSetVersion: string,
 *   travelEstimateDegraded: boolean,
 *   partsDataDegraded: boolean,
 *   candidatePoolSize: number,
 *   truncated: boolean,
 *   exclusionSummary: ExclusionSummaryEntry[],
 *   partsWarning: PartsWarning | null
 * }} RecommendationMeta
 */

/**
 * @typedef {{
 *   data: CandidateDto[],
 *   page: { size: number, hasNext: boolean },
 *   links: { next: string | null },
 *   meta: RecommendationMeta
 * }} RecommendationPage
 */

/**
 * Builds the request URL for a given page.
 *
 * @param {string} workOrderId
 * @param {string | null} cursor  Opaque keyset cursor from the previous page's links.next
 * @param {number} size
 * @returns {string}
 */
function buildUrl(workOrderId, cursor, size) {
  const clamped = Math.min(50, Math.max(1, Math.floor(size)))
  const params = new URLSearchParams()
  params.set('size', String(clamped))
  if (cursor) params.set('cursor', cursor)
  return `/work-orders/${workOrderId}/recommendations?${params.toString()}`
}

/**
 * Extracts the cursor string from the next URL in the page links.
 *
 * @param {RecommendationPage} page
 * @returns {string | undefined}  undefined tells useInfiniteQuery there is no next page
 */
function getNextPageParam(page) {
  const next = page?.links?.next
  if (!next) return undefined
  try {
    const url = new URL(next, window.location.origin)
    return url.searchParams.get('cursor') ?? undefined
  } catch {
    return undefined
  }
}

/**
 * Infinite-scroll hook over the keyset-paginated recommendations endpoint.
 *
 * @param {{
 *   workOrderId: string | null | undefined,
 *   size?: number,
 *   enabled?: boolean
 * }} options
 * @returns {import('@tanstack/react-query').UseInfiniteQueryResult<RecommendationPage>}
 */
export function useRecommendations({ workOrderId, size = 20, enabled = true } = {}) {
  return useInfiniteQuery({
    queryKey: ['dispatch', 'recommendations', workOrderId, size],
    queryFn: ({ pageParam = null, signal }) =>
      get(buildUrl(workOrderId, pageParam, size), { signal }),
    getNextPageParam,
    initialPageParam: null,
    staleTime: RECOMMENDATIONS_STALE_TIME,
    enabled: Boolean(workOrderId && enabled),
  })
}

/**
 * Flattens all loaded pages into a single de-duplicated candidate list.
 * Rank is used as the stable key so re-fetched pages cannot introduce duplicates.
 *
 * @param {import('@tanstack/react-query').InfiniteData<RecommendationPage>} data
 * @returns {CandidateDto[]}
 */
export function flattenCandidates(data) {
  if (!data?.pages) return []
  const seen = new Set()
  return data.pages.flatMap((page) => {
    if (!Array.isArray(page?.data)) return []
    return page.data.filter((c) => {
      if (seen.has(c.rank)) return false
      seen.add(c.rank)
      return true
    })
  })
}

/**
 * Extracts the meta block from the first loaded page (stable across pages).
 *
 * @param {import('@tanstack/react-query').InfiniteData<RecommendationPage> | undefined} data
 * @returns {RecommendationMeta | null}
 */
export function extractMeta(data) {
  return data?.pages?.[0]?.meta ?? null
}
