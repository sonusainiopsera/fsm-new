/**
 * @fileoverview Hook that synchronises page/size/sort/filter state with URL search params.
 *
 * Serialises state to search params on every change so a page link is shareable
 * and a browser refresh restores the exact view. Uses React Router 6's
 * useSearchParams for two-way binding.
 *
 * @module shared/hooks/useUrlPageState
 */
import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { buildPageQuery } from '../../api/pagination.js'

/**
 * @typedef {{
 *   page: number,
 *   size: number,
 *   sort?: string,
 *   filter?: string
 * }} PageState
 */

/**
 * @typedef {{
 *   state: PageState,
 *   setPage: (page: number) => void,
 *   setSize: (size: number) => void,
 *   setSort: (sort: string | undefined) => void,
 *   setFilter: (filter: string | undefined) => void,
 *   resetToFirstPage: () => void
 * }} UseUrlPageStateResult
 */

/**
 * Synchronises page/size/sort/filter with URL search params.
 *
 * @param {{ defaultSize?: number, defaultSort?: string }} [options]
 * @returns {UseUrlPageStateResult}
 */
export function useUrlPageState({ defaultSize = 20, defaultSort } = {}) {
  const [searchParams, setSearchParams] = useSearchParams()

  const state = useMemo(() => {
    const raw = buildPageQuery({
      page: Number(searchParams.get('page') ?? 0),
      size: Number(searchParams.get('size') ?? defaultSize),
      sort: searchParams.get('sort') ?? defaultSort,
    })
    return {
      ...raw,
      filter: searchParams.get('filter') ?? undefined,
    }
  }, [searchParams, defaultSize, defaultSort])

  const update = useCallback((updates) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      Object.entries(updates).forEach(([key, val]) => {
        if (val == null || val === '') {
          next.delete(key)
        } else {
          next.set(key, String(val))
        }
      })
      return next
    }, { replace: true })
  }, [setSearchParams])

  const setPage = useCallback((page) => update({ page }), [update])
  const setSize = useCallback((size) => update({ size, page: 0 }), [update])
  const setSort = useCallback((sort) => update({ sort, page: 0 }), [update])
  const setFilter = useCallback((filter) => update({ filter, page: 0 }), [update])
  const resetToFirstPage = useCallback(() => update({ page: 0 }), [update])

  return { state, setPage, setSize, setSort, setFilter, resetToFirstPage }
}
