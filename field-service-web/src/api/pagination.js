/**
 * @fileoverview Pagination helpers.
 *
 * Enforces the server-side maximum page size of 50 client-side so
 * out-of-range requests are clamped before dispatch (WO-186 constraint).
 * Reads the response envelope's page metadata and next/prev links.
 */

const MAX_PAGE_SIZE = 50
const MIN_PAGE_SIZE = 1
const DEFAULT_PAGE_SIZE = 20

/**
 * @typedef {{
 *   page: number,
 *   size: number,
 *   sort?: string,
 *   cursor?: string
 * }} PageQuery
 */

/**
 * @typedef {{
 *   totalElements: number,
 *   totalPages: number,
 *   page: number,
 *   size: number,
 *   hasNext: boolean,
 *   hasPrev: boolean
 * }} PageMeta
 */

/**
 * @typedef {{
 *   self?: string,
 *   next?: string | null,
 *   prev?: string | null,
 *   first?: string,
 *   last?: string
 * }} PageLinks
 */

/**
 * @typedef {{
 *   data: unknown[],
 *   page: PageMeta,
 *   links: PageLinks
 * }} PagedResponse
 */

/**
 * Builds a validated page query object, clamping size to [1, 50].
 *
 * @param {{ page?: number, size?: number, sort?: string, cursor?: string }} params
 * @returns {PageQuery}
 */
export function buildPageQuery({ page = 0, size = DEFAULT_PAGE_SIZE, sort, cursor } = {}) {
  return {
    page: Math.max(0, Math.floor(page)),
    size: Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, Math.floor(size))),
    ...(sort ? { sort } : {}),
    ...(cursor ? { cursor } : {}),
  }
}

/**
 * Converts a PageQuery to a URL query string.
 *
 * @param {PageQuery} query
 * @returns {string}  e.g. "page=0&size=20&sort=createdAt"
 */
export function toQueryString(query) {
  const params = new URLSearchParams()
  params.set('page', String(query.page))
  params.set('size', String(query.size))
  if (query.sort) params.set('sort', query.sort)
  if (query.cursor) params.set('cursor', query.cursor)
  return params.toString()
}

/**
 * Extracts pagination metadata from a server response envelope.
 *
 * @param {unknown} envelope  Parsed response body
 * @returns {PageMeta}
 */
export function extractPageMeta(envelope) {
  const page = (envelope && typeof envelope === 'object' && 'page' in envelope)
    ? /** @type {any} */ (envelope).page
    : {}

  return {
    totalElements: Number(page.totalElements ?? 0),
    totalPages: Number(page.totalPages ?? 0),
    page: Number(page.page ?? 0),
    size: Number(page.size ?? DEFAULT_PAGE_SIZE),
    hasNext: Boolean(page.hasNext ?? false),
    hasPrev: Boolean(page.hasPrev ?? false),
  }
}

/**
 * Extracts pagination links from a server response envelope.
 *
 * @param {unknown} envelope  Parsed response body
 * @returns {PageLinks}
 */
export function extractLinks(envelope) {
  const links = (envelope && typeof envelope === 'object' && 'links' in envelope)
    ? /** @type {any} */ (envelope).links
    : {}

  return {
    self: links.self ?? undefined,
    next: links.next ?? null,
    prev: links.prev ?? null,
    first: links.first ?? undefined,
    last: links.last ?? undefined,
  }
}

/**
 * Extracts the data array from a server response envelope.
 *
 * @param {unknown} envelope
 * @returns {unknown[]}
 */
export function extractData(envelope) {
  if (envelope && typeof envelope === 'object' && 'data' in envelope) {
    const data = /** @type {any} */ (envelope).data
    return Array.isArray(data) ? data : []
  }
  return []
}

/**
 * Full envelope parse — returns { data, page, links }.
 *
 * @param {unknown} envelope
 * @returns {{ data: unknown[], page: PageMeta, links: PageLinks }}
 */
export function parsePagedEnvelope(envelope) {
  return {
    data: extractData(envelope),
    page: extractPageMeta(envelope),
    links: extractLinks(envelope),
  }
}

/**
 * Returns the next page's cursor from a keyset-paginated response.
 *
 * @param {unknown} envelope
 * @returns {string | null}
 */
export function extractNextCursor(envelope) {
  const links = extractLinks(envelope)
  if (!links.next) return null
  try {
    const url = new URL(links.next, window.location.origin)
    return url.searchParams.get('cursor')
  } catch {
    return null
  }
}

export { MAX_PAGE_SIZE }
