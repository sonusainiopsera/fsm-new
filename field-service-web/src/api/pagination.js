/**
 * Pagination helper for the platform response envelope.
 *
 * Platform envelope for paginated collections:
 * {
 *   "data": [...],
 *   "page": {
 *     "number": 0,
 *     "size": 20,
 *     "totalElements": 105,
 *     "totalPages": 6,
 *     "estimated": false
 *   },
 *   "_links": { "self": "...", "next": "...", "prev": "..." }
 * }
 *
 * Client-side ceiling: MAX_PAGE_SIZE = 50
 * Requests for a larger page size are clamped before dispatch.
 *
 * Sort fields must come from the server allow-list; unknown sort fields are
 * rejected by the server (400). Clients should pass only documented sort keys.
 */

export const MAX_PAGE_SIZE = 50;

/**
 * Builds URLSearchParams for an offset-based paginated request.
 * Clamps pageSize to MAX_PAGE_SIZE before the request is sent.
 *
 * @param {{
 *   page?: number,
 *   pageSize?: number,
 *   sort?: string,
 * }} [params]
 * @returns {URLSearchParams}
 */
export function buildPageParams({ page = 0, pageSize = 20, sort } = {}) {
  const clampedSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
  const params = new URLSearchParams();
  params.set('page', String(page));
  params.set('size', String(clampedSize));
  if (sort) params.set('sort', sort);
  return params;
}

/**
 * Builds URLSearchParams for a keyset-cursor paginated request.
 * Used for recommendation and deep-search endpoints.
 *
 * @param {{
 *   pageSize?: number,
 *   cursor?: string | null,
 *   sort?: string,
 * }} [params]
 * @returns {URLSearchParams}
 */
export function buildKeysetParams({ pageSize = 20, cursor = null, sort } = {}) {
  const clampedSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
  const params = new URLSearchParams();
  params.set('size', String(clampedSize));
  if (cursor) params.set('cursor', cursor);
  if (sort) params.set('sort', sort);
  return params;
}

/**
 * @typedef {{ number: number, size: number, totalElements: number, totalPages: number, estimated: boolean }} PageMeta
 * @typedef {{ self: string | null, next: string | null, prev: string | null }} PageLinks
 * @typedef {{ data: unknown[], page: PageMeta, links: PageLinks }} PaginatedResult
 */

/**
 * Parses a paginated response envelope.
 *
 * @param {unknown} envelope
 * @returns {PaginatedResult}
 */
export function parsePaginatedResponse(envelope) {
  if (!envelope || typeof envelope !== 'object') {
    throw new TypeError('parsePaginatedResponse: expected an object envelope');
  }

  const data = Array.isArray(envelope.data) ? envelope.data : [];

  /** @type {PageMeta} */
  const page = {
    number:        envelope.page?.number        ?? 0,
    size:          envelope.page?.size          ?? data.length,
    totalElements: envelope.page?.totalElements ?? data.length,
    totalPages:    envelope.page?.totalPages    ?? 1,
    estimated:     envelope.page?.estimated     ?? false,
  };

  /** @type {PageLinks} */
  const links = {
    self: envelope._links?.self  ?? null,
    next: envelope._links?.next  ?? null,
    prev: envelope._links?.prev  ?? null,
  };

  return { data, page, links };
}
