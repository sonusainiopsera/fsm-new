/**
 * Unit tests for useConditionalQuery.js — ETag/304 conditional polling.
 *
 * AC-7: 304 Not Modified keeps existing cached data and does not cause re-render.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { useConditionalQuery, DASHBOARD_INTERVAL, PORTAL_INTERVAL } from './useConditionalQuery.js'

// ---------------------------------------------------------------------------
// Mock http.js
// ---------------------------------------------------------------------------

vi.mock('./http.js', () => ({
  request: vi.fn(),
}))

import { request as mockRequest } from './http.js'

// ---------------------------------------------------------------------------
// Test wrapper
// ---------------------------------------------------------------------------

function makeWrapper(queryClient) {
  return function Wrapper({ children }) {
    return React.createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

let queryClient

beforeEach(() => {
  queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
    },
  })
  vi.clearAllMocks()
})

afterEach(() => {
  queryClient.clear()
})

// ---------------------------------------------------------------------------
// Interval constants
// ---------------------------------------------------------------------------

describe('polling interval constants', () => {
  it('DASHBOARD_INTERVAL is 30 seconds', () => {
    expect(DASHBOARD_INTERVAL).toBe(30_000)
  })

  it('PORTAL_INTERVAL is 60 seconds', () => {
    expect(PORTAL_INTERVAL).toBe(60_000)
  })
})

// ---------------------------------------------------------------------------
// Normal 200 fetch
// ---------------------------------------------------------------------------

describe('200 OK response', () => {
  it('returns data from successful fetch', async () => {
    const data = { items: [{ id: 'wo-1' }] }
    mockRequest.mockResolvedValueOnce(data)

    const wrapper = makeWrapper(queryClient)
    const { result } = renderHook(
      () => useConditionalQuery({
        queryKey: ['workOrders'],
        url: '/work-orders',
      }),
      { wrapper }
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(data)
  })
})

// ---------------------------------------------------------------------------
// 304 Not Modified — AC-7 core assertion
// ---------------------------------------------------------------------------

describe('304 Not Modified handling', () => {
  it('keeps existing cached data when server returns 304', async () => {
    const initialData = { items: [{ id: 'wo-1' }] }

    // First fetch: returns data
    mockRequest.mockResolvedValueOnce(initialData)

    const wrapper = makeWrapper(queryClient)
    const { result } = renderHook(
      () => useConditionalQuery({
        queryKey: ['workOrders'],
        url: '/work-orders',
        enabled: true,
      }),
      { wrapper }
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(initialData)

    // Second fetch: returns __notModified (simulates 304)
    mockRequest.mockResolvedValueOnce({ __notModified: true })

    // Trigger a re-fetch
    queryClient.invalidateQueries({ queryKey: ['workOrders'] })

    // Data should remain the same — no re-render with undefined
    await waitFor(() => expect(result.current.isFetching).toBe(false))
    expect(result.current.data).toEqual(initialData)
  })

  it('does not update data on 304 (undefined is not returned to caller)', async () => {
    const initialData = { items: [{ id: 'wo-2' }] }
    mockRequest.mockResolvedValueOnce(initialData)

    const wrapper = makeWrapper(queryClient)
    const { result } = renderHook(
      () => useConditionalQuery({
        queryKey: ['workOrders', 'list'],
        url: '/work-orders?page=0',
      }),
      { wrapper }
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const dataAfterFirst = result.current.data

    // Simulate 304 on second fetch
    mockRequest.mockResolvedValueOnce({ __notModified: true })
    queryClient.invalidateQueries({ queryKey: ['workOrders', 'list'] })
    await waitFor(() => expect(result.current.isFetching).toBe(false))

    expect(result.current.data).toBe(dataAfterFirst)
  })
})

// ---------------------------------------------------------------------------
// select transform
// ---------------------------------------------------------------------------

describe('select transform', () => {
  it('applies select function to fetched data', async () => {
    mockRequest.mockResolvedValueOnce({ items: [{ id: 'wo-3' }], total: 1 })

    const wrapper = makeWrapper(queryClient)
    const { result } = renderHook(
      () => useConditionalQuery({
        queryKey: ['workOrders', 'items'],
        url: '/work-orders',
        select: d => d?.items ?? [],
      }),
      { wrapper }
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([{ id: 'wo-3' }])
  })
})

// ---------------------------------------------------------------------------
// enabled=false
// ---------------------------------------------------------------------------

describe('enabled flag', () => {
  it('does not fetch when enabled=false', async () => {
    const wrapper = makeWrapper(queryClient)
    const { result } = renderHook(
      () => useConditionalQuery({
        queryKey: ['workOrders', 'disabled'],
        url: '/work-orders',
        enabled: false,
      }),
      { wrapper }
    )

    // Allow a tick
    await new Promise(r => setTimeout(r, 20))
    expect(mockRequest).not.toHaveBeenCalled()
    expect(result.current.data).toBeUndefined()
  })
})
